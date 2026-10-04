package fr.erick.journallocal;

import java.util.*;

/** Explicit privacy policy. A tracker signature changes priority, never permission eligibility. */
final class PermissionReviewRules {
    static final String[] GROUPS={"background","special","personal","location","media","capture","nearby","notifications"};
    static final String[] LABELS={"Accès en arrière-plan","Accès spéciaux","Contacts, calendrier, téléphone, SMS et santé","Localisation","Photos, vidéos et fichiers audio","Caméra et microphone","Bluetooth et appareils proches","Notifications"};
    static Set<String> defaults(){return new LinkedHashSet<>(Arrays.asList("background","special"));}
    static String group(String name){
        if(name==null)return "other";
        if(!name.startsWith("android.permission."))return "other";
        if(name.equals("android.permission.ACCESS_BACKGROUND_LOCATION")||name.endsWith("_BACKGROUND")||name.endsWith("_IN_BACKGROUND"))return "background";
        if(!PermissionControlRules.appOp(name).isEmpty())return "special";
        if(!name.startsWith("android.permission."))return "other";
        String n=name.substring(19);
        if(n.equals("CAMERA")||n.equals("RECORD_AUDIO"))return "capture";
        if(n.equals("ACCESS_FINE_LOCATION")||n.equals("ACCESS_COARSE_LOCATION"))return "location";
        if(n.equals("POST_NOTIFICATIONS"))return "notifications";
        if(n.startsWith("BLUETOOTH_")||n.equals("NEARBY_WIFI_DEVICES")||n.equals("UWB_RANGING"))return "nearby";
        if(n.startsWith("READ_MEDIA_")||n.equals("READ_EXTERNAL_STORAGE")||n.equals("WRITE_EXTERNAL_STORAGE"))return "media";
        if(n.startsWith("health.")||Arrays.asList("READ_CONTACTS","WRITE_CONTACTS","GET_ACCOUNTS","READ_CALENDAR","WRITE_CALENDAR","READ_CALL_LOG","WRITE_CALL_LOG","PROCESS_OUTGOING_CALLS","READ_PHONE_STATE","READ_PHONE_NUMBERS","CALL_PHONE","ANSWER_PHONE_CALLS","ADD_VOICEMAIL","USE_SIP","ACCEPT_HANDOVER","READ_SMS","SEND_SMS","RECEIVE_SMS","RECEIVE_MMS","RECEIVE_WAP_PUSH","BODY_SENSORS","ACTIVITY_RECOGNITION").contains(n))return "personal";
        return "other";
    }
    static String label(String group){for(int i=0;i<GROUPS.length;i++)if(GROUPS[i].equals(group))return LABELS[i];return "Autre droit — examen individuel";}
    static boolean selected(String name,boolean canRevoke,String kind,String opMode,boolean hasProfile,boolean outsideProfile,Set<String> groups){
        if(!canRevoke)return false;
        // A raw default AppOp is not proof of an active special access.
        if("appop".equals(kind)&&!Arrays.asList("allow","foreground").contains(opMode))return false;
        return hasProfile?outsideProfile:groups.contains(group(name));
    }
    static String origin(String reason,PermissionControlRules.Grant state){
        if(reason==null||reason.isEmpty())return "NONE";
        if(reason.startsWith("Protection AIV"))return "AIV_PROTECTION";
        if(reason.startsWith("Permission de développement")||reason.startsWith("Continuité AIV"))return "AIV_SUPPORT";
        if(reason.startsWith("Portée UID")||reason.startsWith("AppOp appliquée"))return "UID_SCOPE";
        if(state!=null&&state.fixed())return "ANDROID_LOCK";
        if(reason.startsWith("Permission d’installation"))return "ANDROID_TYPE";
        if(reason.equals("Déjà refusée")||reason.equals("Opération déjà bloquée"))return "ALREADY_DENIED";
        return "OBSERVATION";
    }
    static boolean targetAllowed(String reason,boolean includeProtected){return reason.isEmpty()||(includeProtected&&reason.startsWith("Protection AIV"));}
    static boolean sameTarget(String expected,String actual,boolean includeProtected){return Objects.equals(expected,actual)&&targetAllowed(actual,includeProtected);}
    static int priority(String name,int trackerCount){String g=group(name);int p=g.equals("background")?0:g.equals("special")?10:g.equals("personal")?20:30;return p+(trackerCount>0?0:1);}
    static final class Target {
        final String pkg,name;
        Target(String pkg,String name){this.pkg=pkg;this.name=name;}
    }
    /** All unique selected rights are retained, even when one app crosses a batch boundary. */
    static List<List<Target>> batches(List<Target> input,int maxApps,int maxRights){
        if(maxApps<1||maxRights<1)throw new IllegalArgumentException("Bornes invalides");
        List<List<Target>> result=new ArrayList<>();List<Target> batch=new ArrayList<>();Set<String> packages=new HashSet<>(),seen=new HashSet<>();
        for(Target target:input){
            if(!PermissionControlRules.name(target.pkg)||!PermissionControlRules.name(target.name))throw new IllegalArgumentException("Cible invalide");
            if(!seen.add(target.pkg+"|"+target.name))continue;
            if(batch.size()>=maxRights||(!packages.contains(target.pkg)&&packages.size()>=maxApps)){result.add(batch);batch=new ArrayList<>();packages.clear();}
            batch.add(target);packages.add(target.pkg);
        }
        if(!batch.isEmpty())result.add(batch);return result;
    }
}
