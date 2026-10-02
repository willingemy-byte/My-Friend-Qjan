package fr.erick.journallocal;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Product-tier state for the investor/demo build.
 *
 * This is deliberately NOT a commercial entitlement verifier. A future paid
 * release must replace demo_preview with a server-verified/signed entitlement.
 */
public final class ProductAccess {
    public static final int FREE=1,PAID=2,IT=3;
    private static final String PREFS="aiv_product_access";
    private static final String KEY_DEMO_TIER="demo_preview_tier";
    private ProductAccess(){}

    public static int demoTier(Context context){
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt(KEY_DEMO_TIER,FREE);
    }

    public static void setDemoTier(Context context,int tier){
        int safe=tier<FREE?FREE:tier>IT?IT:tier;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt(KEY_DEMO_TIER,safe).apply();
    }

    public static boolean paidEnabled(Context context){return demoTier(context)>=PAID;}

    public static JSONObject status(Context context){
        int tier=demoTier(context);
        return EventStore.object(
            "schema","aiv-product-access/1",
            "tier",tier,
            "tier_name",tier>=IT?"TI":tier>=PAID?"PAID":"FREE",
            "source","DEMO_PREVIEW",
            "commercially_verified",false,
            "scope","Aperçu investisseur seulement; ne constitue pas un entitlement commercial vérifié."
        );
    }
}
