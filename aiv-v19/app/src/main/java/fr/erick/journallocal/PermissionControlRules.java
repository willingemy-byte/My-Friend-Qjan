package fr.erick.journallocal;

import java.util.*;
import java.util.regex.*;

/** Exact Android identities and conservative parsing of Shell observations. No OS calls. */
final class PermissionControlRules {
    private static final Pattern NAME=Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern USER=Pattern.compile("^\\s*User (\\d+):.*$");
    private static final Pattern GRANT=Pattern.compile("^\\s*([A-Za-z0-9_.]+): granted=(true|false), flags=\\[([^]]*)\\]\\s*$");
    static boolean name(String s){return s!=null&&s.length()<=255&&NAME.matcher(s).matches();}
    static boolean packageName(String s){return "android".equals(s)||name(s);}
    static String quote(String s){if(!packageName(s))throw new IllegalArgumentException("Identité Android invalide");return "'"+s+"'";}
    static final class Grant {
        final boolean granted; final String flags;
        Grant(boolean granted,String flags){this.granted=granted;this.flags=flags(flags);}
        boolean fixed(){return contains(flags,"SYSTEM_FIXED")||contains(flags,"POLICY_FIXED");}
    }
    static String flags(String s){
        TreeSet<String> out=new TreeSet<>();
        for(String t:(s==null?"":s).split("[|,\\s]+"))if(!t.isEmpty())out.add(t);
        return String.join("|",out);
    }
    static boolean contains(String flags,String value){return Arrays.asList(flags(flags).split("\\|",-1)).contains(value);}
    /** Never substitute another profile or the install-permission section for runtime state. */
    static Map<String,Grant> runtime(String text,String pkg,int user){
        Map<String,Grant> out=new LinkedHashMap<>();
        boolean inPackage=false,inUser=false,inRuntime=false;
        for(String line:text.split("\\r?\\n")){
            String trimmed=line.trim();
            if(trimmed.startsWith("Package [")){
                inPackage=trimmed.startsWith("Package ["+pkg+"] (");inUser=false;inRuntime=false;continue;
            }
            if(!inPackage)continue;
            if(trimmed.equals("Shared users:")||trimmed.equals("Dexopt state:")||trimmed.equals("Compiler stats:")){
                inPackage=false;inUser=false;inRuntime=false;continue;
            }
            Matcher u=USER.matcher(line);
            if(u.matches()){inUser=Integer.parseInt(u.group(1))==user;inRuntime=false;continue;}
            if(inUser&&trimmed.equals("runtime permissions:")){inRuntime=true;continue;}
            if(!inUser||!inRuntime)continue;
            Matcher m=GRANT.matcher(line);
            if(m.matches())out.put(m.group(1),new Grant(Boolean.parseBoolean(m.group(2)),m.group(3)));
        }
        return out;
    }
    static String runtimeReason(int protection,boolean granted,Grant shell,String targetReason){
        if(!granted)return "Déjà refusée";
        if(protection<0)return "Type de permission inconnu";
        if((protection&32)!=0)return "Permission de développement : relevé spécifique non pris en charge par AIV";
        if((protection&15)!=1)return "Permission d’installation, de signature ou interne : pm revoke non applicable";
        if(shell==null)return "État Shell non vérifié : autoriser Shizuku puis actualiser";
        if(shell.granted!=granted)return "État Android/Shell différent : actualiser";
        if(shell.fixed())return "Verrouillée par Android ou une politique (SYSTEM_FIXED / POLICY_FIXED)";
        if(!targetReason.isEmpty())return targetReason;
        return "";
    }
    static String appOp(String permission){
        switch(permission){
            case "android.permission.SYSTEM_ALERT_WINDOW":return "SYSTEM_ALERT_WINDOW";
            case "android.permission.WRITE_SETTINGS":return "WRITE_SETTINGS";
            case "android.permission.PACKAGE_USAGE_STATS":return "GET_USAGE_STATS";
            case "android.permission.REQUEST_INSTALL_PACKAGES":return "REQUEST_INSTALL_PACKAGES";
            case "android.permission.MANAGE_EXTERNAL_STORAGE":return "MANAGE_EXTERNAL_STORAGE";
            case "android.permission.SCHEDULE_EXACT_ALARM":return "SCHEDULE_EXACT_ALARM";
            default:return "";
        }
    }
    static final class Op {
        final String mode; final boolean uidScope;
        Op(String mode,boolean uidScope){this.mode=mode;this.uidScope=uidScope;}
    }
    static boolean mode(String s){return Arrays.asList("allow","ignore","deny","default","foreground").contains(s);}
    static Op appOpState(String text,String op,int exit,boolean complete){
        if(exit!=0||!complete)return new Op("",false);
        boolean uid=false;
        Pattern p=Pattern.compile("^\\s*"+Pattern.quote(op)+":\\s*(allow|ignore|deny|default|foreground)(?:[;\\s].*)?$");
        String mode="";
        for(String line:text.split("\\r?\\n")){
            if(line.contains("Uid mode:")&&line.contains(op+":"))uid=true;
            Matcher m=p.matcher(line);if(m.matches())mode=m.group(1);
        }
        if(mode.isEmpty()&&text.trim().matches("No operations\\.(?:\\r?\\nDefault mode: (?:allow|ignore|deny|default|foreground))?"))mode="default";
        return new Op(mode,uid);
    }
    static int revokeOrder(String permission){
        if(permission.endsWith("_BACKGROUND")||permission.endsWith("_IN_BACKGROUND")||permission.equals("android.permission.ACCESS_BACKGROUND_LOCATION"))return 0;
        if(permission.equals("android.permission.ACCESS_FINE_LOCATION"))return 1;
        if(permission.equals("android.permission.ACCESS_COARSE_LOCATION"))return 2;
        return 3;
    }
    static String command(String pkg,int user,String kind,String permission,String value){
        if(!name(pkg)||!name(permission)||user<0)throw new IllegalArgumentException("Cible Shell invalide");
        if("permission".equals(kind)&&("grant".equals(value)||"revoke".equals(value)))
            return "pm "+value+" --user "+user+" "+quote(pkg)+" "+quote(permission);
        String op=appOp(permission);
        if("appop".equals(kind)&&!op.isEmpty()&&mode(value))
            return "cmd appops set --user "+user+" "+quote(pkg)+" "+op+" "+value;
        throw new IllegalArgumentException("Action Shell non prise en charge");
    }
    static String outcome(int code,boolean observed){return code==0&&observed?"confirmed":code==0?"no_effect":observed?"unconfirmed_change":"refused";}
    static boolean fresh(long now,long created,long ttl){long age=now-created;return age>=0&&age<=ttl;}
    static boolean restoreAllowed(String outcome,boolean sameIdentity,String current,String after){
        return "confirmed".equals(outcome)&&sameIdentity&&current!=null&&current.equals(after);
    }
}
