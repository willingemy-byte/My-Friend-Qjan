package fr.erick.journallocal;

import java.util.regex.Pattern;

/** Pure policy and parsing helpers, also exercised on the host JVM. */
final class ControlRules {
    private static final Pattern PACKAGE=Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    static boolean validPackage(String value){return value!=null&&PACKAGE.matcher(value).matches();}
    static boolean validAction(String value){return "stop".equals(value)||"disable".equals(value);}
    static String enabledCommand(int state){
        switch(state){case 0:return "default-state";case 1:return "enable";case 2:return "disable";case 3:return "disable-user";case 4:return "disable-until-used";default:throw new IllegalArgumentException("État inconnu");}
    }
    static boolean reached(String action,int enabled,boolean stopped){return "disable".equals(action)?enabled==3:stopped;}
    static String outcome(int exit,boolean verified){return verified?"confirmed":exit==0?"no_effect":"refused";}
    static boolean sameIdentity(String before,String after){return before!=null&&!before.isEmpty()&&before.equals(after);}
}
