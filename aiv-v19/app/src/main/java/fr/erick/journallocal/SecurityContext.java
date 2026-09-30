package fr.erick.journallocal;
import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import org.json.*;
/** Offline bibliography, not a vulnerability scanner or an attribution source. */
final class SecurityContext {
    static JSONArray forPackages(Context c,JSONArray packages){
        JSONArray result=new JSONArray();
        for(int i=0;i<packages.length();i++){
            String name=packages.optString(i);if(!"com.sec.android.diagmonagent".equals(name))continue;
            String version="Non accessible";long code=-1;
            try{PackageInfo p=c.getPackageManager().getPackageInfo(name,0);version=p.versionName;code=Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}catch(PackageManager.NameNotFoundException ignored){}
            boolean watch=c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
            result.put(EventStore.object("package",name,"installed_version",version,"installed_version_code",code,"cve","CVE-2025-20912","reference_checked","2026-09-10","vendor_severity","Moderate",
                "documented_product","Galaxy Watch — Android Watch 14, avant SMR Mar-2025 Release 1","summary","Un défaut de permissions permet un accès local à des données de la montre.",
                "applicability",!watch?"Appareil hors du produit documenté (Galaxy Watch)":"Version du composant et correctif à vérifier; vulnérabilité non établie","android_security_patch",Build.VERSION.SECURITY_PATCH,
                "source","https://security.samsungmobile.com/securityUpdate.smsb?year=2025&month=03","interpretation","Contexte bibliographique d’un paquet candidat; ne prouve ni sa responsabilité dans ce flux, ni une attaque, ni une vulnérabilité de ce téléphone."));
        }return result;
    }
}