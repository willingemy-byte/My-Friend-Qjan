package fr.erick.journallocal;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Cryptographic fingerprints for installed packages and UID groups.
 *
 * AIV does not inject a private key into third-party apps. Their Android APK
 * signing certificates are the cryptographic material AIV can actually observe.
 * A package identity is stable while package name, profile and current signer set
 * stay the same. Network attribution still follows Android's UID ownership rules.
 */
final class AppIdentity {
    private AppIdentity(){}

    static JSONObject forPackage(Context context,String packageName)throws Exception{
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        return forPackage(context,context.getPackageManager().getPackageInfo(packageName,flags));
    }

    static JSONObject forPackage(Context context,PackageInfo info)throws Exception{
        if(info==null||info.applicationInfo==null)throw new IllegalArgumentException("Paquet sans identité Android");
        JSONObject certs=ApkEvidence.certificates(info);
        List<String> current=sorted(certs.optJSONArray("current_sha256"));
        List<String> history=sorted(certs.optJSONArray("history_sha256"));
        int uid=info.applicationInfo.uid,profile=uid/100000;
        String canonical="aiv-app-identity/1\npackage="+info.packageName+"\nprofile="+profile+"\nsigners="+join(current);
        JSONObject out=EventStore.object(
            "schema","aiv-app-identity/1",
            "type","package_signer",
            "package_name",info.packageName,
            "profile_id",profile,
            "uid",uid,
            "app_identity_id",sha256(canonical),
            "current_signer_sha256",array(current),
            "signing_history_sha256",array(history),
            "version_code",Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode,
            "identity_source","PackageManager signing certificates",
            "identity_scope","Empreinte locale du paquet et de ses signataires; elle change si le signataire courant change. Ce n'est pas une preuve d'identité civile de l'éditeur.");
        String[] peers=context.getPackageManager().getPackagesForUid(uid);
        out.put("uid_package_count",peers==null?0:peers.length);
        out.put("network_attribution_unique",uid%100000>=10000&&peers!=null&&peers.length==1);
        return out;
    }

    static JSONObject forUid(Context context,int uid,String[] packages){
        try{
            int profile=uid/100000;
            String actorCanonical="aiv-network-actor/1\nprofile="+profile+"\nuid="+uid;
            String networkActorId=sha256(actorCanonical);
            if(packages==null||packages.length==0)return EventStore.object(
                "schema","aiv-app-identity/2","type","uid_only","uid",uid,"profile_id",profile,
                "network_actor_identity_id",networkActorId,
                "uid_identity_id",networkActorId,
                "status","PACKAGE_LIST_UNAVAILABLE",
                "network_attribution","UID_ONLY",
                "identity_scope","Identité AIV stable du propriétaire réseau Android (profil + UID). Elle ne prétend pas identifier une application précise derrière un service intermédiaire.");
            List<String> names=new ArrayList<>();
            Collections.addAll(names,packages);
            Collections.sort(names);
            if(uid%100000>=10000&&names.size()==1){
                JSONObject one=forPackage(context,names.get(0));
                one.put("network_attribution","UNIQUE_UID_PACKAGE");
                one.put("network_actor_identity_id",networkActorId);
                return one;
            }
            JSONArray members=new JSONArray();
            List<String> memberIds=new ArrayList<>();
            for(String name:names){
                try{
                    JSONObject item=forPackage(context,name);
                    members.put(EventStore.object(
                        "package_name",name,
                        "app_identity_id",item.optString("app_identity_id"),
                        "current_signer_sha256",item.optJSONArray("current_signer_sha256")));
                    memberIds.add(name+"="+item.optString("app_identity_id"));
                }catch(Exception e){
                    members.put(EventStore.object("package_name",name,"status","UNAVAILABLE","error",e.getClass().getSimpleName()));
                    memberIds.add(name+"=unavailable");
                }
            }
            String type=uid%100000<10000?"android_uid_group":"shared_uid_group";
            String memberCanonical="aiv-uid-members/1\ntype="+type+"\nprofile="+profile+"\nuid="+uid+"\nmembers="+join(memberIds);
            return EventStore.object(
                "schema","aiv-app-identity/2",
                "type",type,
                "uid",uid,
                "profile_id",profile,
                "network_actor_identity_id",networkActorId,
                "uid_identity_id",networkActorId,
                "member_set_fingerprint",sha256(memberCanonical),
                "members",members,
                "network_attribution","NON_UNIQUE",
                "identity_scope","Identité AIV stable du propriétaire réseau Android (profil + UID). Les paquets membres restent des candidats; AIV peut enrichir la provenance plus tard sans réécrire l'observation originale.");
        }catch(Exception e){
            return EventStore.object("schema","aiv-app-identity/1","type","unavailable","uid",uid,"status","UNAVAILABLE","error",e.getClass().getSimpleName());
        }
    }

    private static List<String> sorted(JSONArray values){
        List<String> out=new ArrayList<>();
        if(values!=null)for(int i=0;i<values.length();i++){String v=values.optString(i);if(v!=null&&!v.isEmpty())out.add(v);}
        Collections.sort(out);
        return out;
    }

    private static JSONArray array(List<String> values){
        JSONArray out=new JSONArray();
        for(String value:values)out.put(value);
        return out;
    }

    private static String join(List<String> values){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<values.size();i++){if(i>0)out.append(',');out.append(values.get(i));}
        return out.toString();
    }

    private static String sha256(String value)throws Exception{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder(digest.length*2);
        for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
}
