package fr.erick.journallocal;

/** Generated public policy. No purchase/authentication check is performed here. */
public final class AccessPolicy {
    private AccessPolicy() {}
    public static final int TIER_FREE=1, TIER_PAID=2, TIER_IT=3;
    public static final int DISTRIBUTION_TIER=3;
    public static final String CATALOG_JSON="{\"distribution_profile\":\"personal\",\"reserved_tiers\":[],\"schema\":\"aiv-access-policy/1\",\"service_minimum_tiers\":{\"analysis.local\":1,\"anomaly.read\":1,\"export.local\":1,\"fleet.enroll\":3,\"fleet.policy\":3,\"fleet.report\":3,\"fleet.view\":3,\"journal.read\":1,\"journal.record\":1,\"network.capture\":1,\"permissions.audit\":1,\"screen.integrity\":1,\"screen.integrity.overlay\":1,\"shizuku.control\":2,\"shizuku.restore\":2,\"tracker.inspect\":1,\"upgrade.checkout\":1,\"vpn.identity.sign\":2,\"vpn.identity.verify\":2},\"tiers\":{\"free\":1,\"it\":3,\"paid\":2}}";
    public static int minimumTier(String service) {
        if(service==null)return -1;
        switch(service) {
            case "analysis.local": return 1;
            case "anomaly.read": return 1;
            case "export.local": return 1;
            case "fleet.enroll": return 3;
            case "fleet.policy": return 3;
            case "fleet.report": return 3;
            case "fleet.view": return 3;
            case "journal.read": return 1;
            case "journal.record": return 1;
            case "network.capture": return 1;
            case "permissions.audit": return 1;
            case "screen.integrity": return 1;
            case "screen.integrity.overlay": return 1;
            case "shizuku.control": return 2;
            case "shizuku.restore": return 2;
            case "tracker.inspect": return 1;
            case "upgrade.checkout": return 1;
            case "vpn.identity.sign": return 2;
            case "vpn.identity.verify": return 2;
            default: return -1;
        }
    }
    /** Caller must obtain verifiedTier from trusted entitlement state, never a UI preference. */
    public static boolean allows(String service, int verifiedTier) {
        return allowsMinimum(minimumTier(service), verifiedTier);
    }
    static boolean allowsMinimum(int minimumTier, int verifiedTier) {
        if(verifiedTier!=TIER_FREE && verifiedTier!=TIER_PAID && verifiedTier!=TIER_IT)return false;
        if(minimumTier!=TIER_FREE && minimumTier!=TIER_PAID && minimumTier!=TIER_IT)return false;
        return verifiedTier>=minimumTier;
    }
}
