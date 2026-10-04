package fr.erick.journallocal;

import java.util.*;

public final class PermissionReviewRulesTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("case "+checks);}
    public static void main(String[] args){
        Set<String> defaults=PermissionReviewRules.defaults();
        check(defaults.equals(new LinkedHashSet<>(Arrays.asList("background","special"))));
        check(PermissionReviewRules.group("android.permission.ACCESS_BACKGROUND_LOCATION").equals("background"));
        check(PermissionReviewRules.group("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND").equals("background"));
        check(PermissionReviewRules.group("android.permission.READ_CONTACTS").equals("personal"));
        check(PermissionReviewRules.group("android.permission.health.READ_HEART_RATE").equals("personal"));
        check(PermissionReviewRules.group("android.permission.READ_MEDIA_IMAGES").equals("media"));
        check(PermissionReviewRules.group("android.permission.CAMERA").equals("capture"));
        check(PermissionReviewRules.group("android.permission.BLUETOOTH_SCAN").equals("nearby"));
        check(PermissionReviewRules.group("android.permission.INTERNET").equals("other"));
        check(PermissionReviewRules.group("com.vendor.permission.READ_CONTACTS").equals("other"));
        check(PermissionReviewRules.group("com.vendor.permission.SOMETHING_BACKGROUND").equals("other"));
        check(PermissionReviewRules.selected("android.permission.ACCESS_BACKGROUND_LOCATION",true,"permission","",false,false,defaults));
        check(!PermissionReviewRules.selected("android.permission.ACCESS_BACKGROUND_LOCATION",false,"permission","",false,false,defaults));
        check(!PermissionReviewRules.selected("android.permission.CAMERA",true,"permission","",false,false,defaults));
        check(PermissionReviewRules.selected("android.permission.CAMERA",true,"permission","",true,true,defaults));
        check(!PermissionReviewRules.selected("android.permission.ACCESS_BACKGROUND_LOCATION",true,"permission","",true,false,defaults));
        check(!PermissionReviewRules.selected("android.permission.SYSTEM_ALERT_WINDOW",true,"appop","default",false,false,defaults));
        check(PermissionReviewRules.selected("android.permission.SYSTEM_ALERT_WINDOW",true,"appop","allow",false,false,defaults));
        check(!PermissionReviewRules.selected("android.permission.SYSTEM_ALERT_WINDOW",true,"appop","ignore",false,false,defaults));
        check(PermissionReviewRules.origin("Protection AIV : rôle actif",null).equals("AIV_PROTECTION"));
        check(PermissionReviewRules.origin("Portée UID partagé",null).equals("UID_SCOPE"));
        check(PermissionReviewRules.origin("Verrouillée",new PermissionControlRules.Grant(true,"SYSTEM_FIXED")).equals("ANDROID_LOCK"));
        check(PermissionReviewRules.origin("État Shell non vérifié",null).equals("OBSERVATION"));
        check(PermissionReviewRules.origin("Permission de développement : relevé spécifique",null).equals("AIV_SUPPORT"));
        check(!PermissionReviewRules.targetAllowed("Continuité AIV : exécuteur",true));
        check(PermissionReviewRules.targetAllowed("Protection AIV : rôle actif",true));
        check(!PermissionReviewRules.targetAllowed("Protection AIV : rôle actif",false));
        check(!PermissionReviewRules.targetAllowed("Portée UID partagé",true));
        check(PermissionReviewRules.sameTarget("Protection AIV : clavier","Protection AIV : clavier",true));
        check(!PermissionReviewRules.sameTarget("","Protection AIV : clavier",true));
        List<PermissionReviewRules.Target> targets=new ArrayList<>();
        for(int i=0;i<501;i++)targets.add(new PermissionReviewRules.Target("com.example.one","com.example.RIGHT_"+i));
        targets.add(targets.get(0));
        List<List<PermissionReviewRules.Target>> batches=PermissionReviewRules.batches(targets,50,500);
        check(batches.size()==2&&batches.get(0).size()==500&&batches.get(1).size()==1);
        targets.clear();for(int i=0;i<51;i++)targets.add(new PermissionReviewRules.Target("com.example.app"+i,"android.permission.CAMERA"));
        batches=PermissionReviewRules.batches(targets,50,500);check(batches.size()==2&&batches.get(0).size()==50&&batches.get(1).size()==1);
        check(PermissionReviewRules.priority("android.permission.INTERNET",100)>PermissionReviewRules.priority("android.permission.ACCESS_BACKGROUND_LOCATION",0));
        check(!PermissionReviewRules.selected("android.permission.INTERNET",false,"permission","",false,false,new HashSet<>(Arrays.asList(PermissionReviewRules.GROUPS))));
        check(PermissionReviewRules.batches(Collections.emptyList(),50,500).isEmpty());
        boolean rejects=false;try{PermissionReviewRules.batches(Arrays.asList(new PermissionReviewRules.Target("com.x; id","android.permission.CAMERA")),50,500);}catch(IllegalArgumentException e){rejects=true;}check(rejects);
        System.out.println("PermissionReviewRules: "+checks+" cases passed");
    }
}
