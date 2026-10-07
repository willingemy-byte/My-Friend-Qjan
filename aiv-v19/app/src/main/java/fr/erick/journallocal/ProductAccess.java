package fr.erick.journallocal;
import android.content.Context;
import org.json.JSONObject;
/** Distribution label is never proof of payment. Demo preferences cannot unlock control. */
public final class ProductAccess {
 public static final int FREE=1,PAID=2,IT=3;
 private static Context app;
 private ProductAccess(){}
 public static synchronized void initialize(Context c){app=c.getApplicationContext();}
 public static boolean ownerBuild(){return "OWNER_INTERNAL".equals(EditionConfig.EDITION)&&!EditionConfig.TEST_PREVIEW;}
 public static int verifiedTier(){if(ownerBuild())return IT;Context c=app;return c!=null&&"FOUNDER_FULL".equals(EditionConfig.EDITION)&&LicenseClient.active(c)?IT:FREE;}
 public static boolean founderPreview(){return EditionConfig.TEST_PREVIEW&&"FOUNDER_FULL".equals(EditionConfig.EDITION);}
 public static boolean fullUi(Context c){return founderPreview()||paidEnabled(c);}
 public static int demoTier(Context c){initialize(c);return founderPreview()?IT:verifiedTier();}
 public static void setDemoTier(Context c,int tier){initialize(c);}
 public static boolean paidEnabled(Context c){initialize(c);return verifiedTier()>=PAID;}
 public static boolean shizukuEnabled(){return verifiedTier()>=PAID;}
 public static void requireFounder(){if(!shizukuEnabled())throw new SecurityException("Information supplémentaire disponible avec AIV complet / Shizuku");}
 public static JSONObject status(Context c){initialize(c);return EventStore.object("schema","aiv-product-access/2","edition",EditionConfig.EDITION,"tier",verifiedTier(),"tier_name",paidEnabled(c)?"FOUNDER_FULL":"FREE","source",ownerBuild()?"OWNER_DEV_OVERRIDE":"SIGNED_ENTITLEMENT","commercially_verified",LicenseClient.active(c),"owner_build",ownerBuild());}
}
