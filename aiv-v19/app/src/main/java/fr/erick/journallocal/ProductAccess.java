package fr.erick.journallocal;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Product-tier state.
 *
 * AIV 2.0.2 owner/development build is intentionally unlocked at TI level so
 * the device owner can exercise Shizuku control and restore without being
 * blocked by the future commercial entitlement gate.
 *
 * This is NOT a commercial entitlement verifier. A future paid release must
 * replace the owner override with a server-verified/signed entitlement.
 */
public final class ProductAccess {
    public static final int FREE=1,PAID=2,IT=3;
    private static final boolean OWNER_BUILD=true;
    private static final String PREFS="aiv_product_access";
    private static final String KEY_DEMO_TIER="demo_preview_tier";
    private ProductAccess(){}

    public static boolean ownerBuild(){return OWNER_BUILD;}

    public static int demoTier(Context context){
        if(OWNER_BUILD)return IT;
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(KEY_DEMO_TIER,FREE);
    }

    public static void setDemoTier(Context context,int tier){
        int safe=OWNER_BUILD?IT:(tier<FREE?FREE:tier>IT?IT:tier);
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(KEY_DEMO_TIER,safe).apply();
    }

    public static boolean paidEnabled(Context context){return demoTier(context)>=PAID;}

    public static JSONObject status(Context context){
        int tier=demoTier(context);
        return EventStore.object(
            "schema","aiv-product-access/1",
            "tier",tier,
            "tier_name",tier>=IT?"TI":tier>=PAID?"PAID":"FREE",
            "source",OWNER_BUILD?"OWNER_DEV_OVERRIDE":"DEMO_PREVIEW",
            "commercially_verified",false,
            "owner_build",OWNER_BUILD,
            "scope",OWNER_BUILD
                ?"Build propriétaire/développement : contrôle local déverrouillé au niveau TI; aucun paiement requis."
                :"Aperçu investisseur seulement; ne constitue pas un entitlement commercial vérifié."
        );
    }
}
