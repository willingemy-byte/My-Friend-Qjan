package fr.erick.journallocal;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import org.json.*;

/** Only documented Android UI actions. No accessibility automation, root, or device-owner escalation. */
public final class DefenseActions {
    static final int UNINSTALL_REQUEST=230, SETTINGS_REQUEST=231;
    private DefenseActions(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("defense-actions",0);}
    static void launch(Activity activity,String pkg,String action,String expectedStamp)throws Exception{
        if(!prefs(activity).getString("package","").isEmpty())throw new IllegalArgumentException("Une action est déjà en attente. Revenir d’Android ou vérifier son résultat.");
        DefenseStore store=DefenseStore.get(activity);JSONObject dossier=store.detail(pkg,0).getJSONObject("app");
        JSONObject fresh=PermissionAudit.get(activity).inspectCurrent(pkg),assessment=store.assess(fresh);
        if(!expectedStamp.equals(DefenseStore.stamp(fresh))||!expectedStamp.equals(dossier.optString("stamp"))){PermissionAudit.get(activity).scan();throw new IllegalArgumentException("Application modifiée depuis le relevé. Nouvel inventaire demandé; réexaminer la fiche.");}
        Intent intent;int request=SETTINGS_REQUEST;Uri uri=Uri.parse("package:"+pkg);
        if("uninstall".equals(action)){
            if(!"TO_REVIEW".equals(dossier.optString("state"))||!assessment.optBoolean("can_request_uninstall"))throw new IllegalArgumentException("Retrait direct indisponible pour ce dossier. Vérifier ses réglages Android.");
            intent=new Intent(Intent.ACTION_UNINSTALL_PACKAGE,uri).putExtra(Intent.EXTRA_RETURN_RESULT,true);request=UNINSTALL_REQUEST;
        }else if("settings".equals(action))intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,uri);
        else{
            boolean declared=false;JSONArray available=assessment.getJSONArray("special_actions");for(int i=0;i<available.length();i++)if(action.equals(available.getString(i)))declared=true;
            if(!declared)throw new IllegalArgumentException("Accès spécial non recensé pour cette application.");
            String setting="unknown_sources".equals(action)?Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES:"overlay".equals(action)?Settings.ACTION_MANAGE_OVERLAY_PERMISSION:"write_settings".equals(action)?Settings.ACTION_MANAGE_WRITE_SETTINGS:"usage".equals(action)?Settings.ACTION_USAGE_ACCESS_SETTINGS:null;
            if(setting==null)throw new IllegalArgumentException("Action non reconnue.");intent=new Intent(setting,uri);
        }
        // Preserve evidence before allowing Android to change the installed package.
        store.action(pkg,"ACTION_REQUESTED",EventStore.object("action",action,"before",fresh,"assessment",assessment,"source","Choix utilisateur depuis Ménage","result","En attente d’Android"));
        if(!prefs(activity).edit().putString("package",pkg).putString("action",action).putLong("requested_ms",System.currentTimeMillis()).commit())throw new IllegalStateException("Enregistrement de l’action impossible.");
        try{activity.startActivityForResult(intent,request);}
        catch(ActivityNotFoundException e){
            if(request==UNINSTALL_REQUEST){finishFailure(activity,pkg,action,e);throw e;}
            try{store.action(pkg,"SETTINGS_FALLBACK",EventStore.object("action",action,"reason","Écran spécifique non disponible; fiche Android de l’application"));activity.startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,uri),request);}
            catch(Exception fallback){finishFailure(activity,pkg,action,fallback);throw fallback;}
        }catch(Exception e){finishFailure(activity,pkg,action,e);throw e;}
    }
    private static void finishFailure(Context c,String pkg,String action,Exception error)throws Exception{
        prefs(c).edit().clear().commit();DefenseStore.get(c).action(pkg,"ACTION_FAILED",EventStore.object("action",action,"error",error.getClass().getSimpleName(),"change_confirmed",false));
    }
    static void returned(Activity activity,int request,int result)throws Exception{
        String pkg=prefs(activity).getString("package",""),action=prefs(activity).getString("action","");if(pkg.isEmpty())return;
        DefenseStore store=DefenseStore.get(activity);boolean absent=false;
        try{activity.getPackageManager().getApplicationInfo(pkg,PackageManager.MATCH_DISABLED_COMPONENTS);}catch(PackageManager.NameNotFoundException e){absent=true;}
        String outcome=DefenseRules.removalOutcome(result==Activity.RESULT_OK,result==Activity.RESULT_CANCELED,absent);
        if(request==UNINSTALL_REQUEST&&"UNINSTALL_CONFIRMED".equals(outcome))store.confirmRemoval(pkg);
        else store.action(pkg,request==UNINSTALL_REQUEST?outcome:"SETTINGS_RETURNED",
            EventStore.object("action",action,"android_result",result,"package_absent",absent,"change_confirmed",false,"scope",request==UNINSTALL_REQUEST?"Le retrait n’est pas confirmé par ce retour Android.":"Retour des réglages; un nouveau relevé compare les permissions. Les accès spéciaux d’autres apps restent non vérifiés."));
        prefs(activity).edit().clear().commit();PermissionAudit.get(activity).scan();
    }
    /** Recover from process death/OEM screens not returning an Activity result. Never invent success. */
    static void recover(Context c)throws Exception{
        String pkg=prefs(c).getString("package","");if(pkg.isEmpty())return;
        DefenseStore.get(c).action(pkg,"RESULT_UNKNOWN",EventStore.object("action",prefs(c).getString("action",""),"scope","Résultat Android non reçu; reprise et nouvel inventaire demandés."));
        prefs(c).edit().clear().commit();PermissionAudit.get(c).scan();
    }
}
