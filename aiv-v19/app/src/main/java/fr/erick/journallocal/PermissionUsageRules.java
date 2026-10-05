package fr.erick.journallocal;

import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.*;
import java.util.regex.*;

/** Parses only the current per-UID/package AppOps state, never aggregate history. */
final class PermissionUsageRules {
    static final long NEAR_MS=60000;
    static final class Operation {
        final String permission,label;
        Operation(String permission,String label){this.permission=permission;this.label=label;}
    }
    static final Map<String,Operation> OPERATIONS=new LinkedHashMap<>();
    static {
        op("READ_CONTACTS","READ_CONTACTS","Lecture des contacts");
        op("WRITE_CONTACTS","WRITE_CONTACTS","Modification des contacts");
        op("RECORD_AUDIO","RECORD_AUDIO","Microphone");
        op("CAMERA","CAMERA","Caméra");
        op("COARSE_LOCATION","ACCESS_COARSE_LOCATION","Position approximative");
        op("FINE_LOCATION","ACCESS_FINE_LOCATION","Position précise");
        op("READ_SMS","READ_SMS","Lecture des SMS");
        op("SEND_SMS","SEND_SMS","Envoi de SMS");
        op("RECEIVE_SMS","RECEIVE_SMS","Réception de SMS");
        op("READ_CALL_LOG","READ_CALL_LOG","Lecture du journal d’appels");
        op("WRITE_CALL_LOG","WRITE_CALL_LOG","Modification du journal d’appels");
        op("READ_PHONE_STATE","READ_PHONE_STATE","État du téléphone");
        op("READ_CALENDAR","READ_CALENDAR","Lecture du calendrier");
        op("WRITE_CALENDAR","WRITE_CALENDAR","Modification du calendrier");
        op("READ_MEDIA_IMAGES","READ_MEDIA_IMAGES","Images");
        op("READ_MEDIA_VIDEO","READ_MEDIA_VIDEO","Vidéos");
        op("READ_MEDIA_AUDIO","READ_MEDIA_AUDIO","Fichiers audio");
        op("POST_NOTIFICATION","POST_NOTIFICATIONS","Affichage de notification");
        OPERATIONS.put("READ_CLIPBOARD",new Operation("","Lecture du presse-papiers"));
    }
    private static void op(String name,String permission,String label){OPERATIONS.put(name,new Operation("android.permission."+permission,label));}
    static final class Entry {
        int uid,proxyUid=-1;String pkg,op,tag="",key="",kind,proxyPackage="";
        long at,end,runningSince;boolean running;
        String dimension(){return uid+"|"+pkg+"|"+op+"|"+tag+"|"+key+"|"+kind;}
    }
    static final class Snapshot {
        final List<Entry> entries=new ArrayList<>();boolean recognized;int malformed;
    }
    private static final Pattern UID=Pattern.compile("Uid ([a-zA-Z0-9]+):");
    private static final Pattern PKG=Pattern.compile("Package ([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*):");
    private static final Pattern OP=Pattern.compile("([A-Z][A-Z_0-9]*) \\([^)]*\\):");
    private static final Pattern ACCESS=Pattern.compile("(Access|Reject):\\s+(\\S+)\\s+(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\(.*");
    private static final Pattern PROXY=Pattern.compile("proxy\\[uid=([^,]+), pkg=([^,\\]]+)(?:, attributionTag=[^\\]]*)?\\]");
    private static final Pattern DURATION=Pattern.compile("(\\d+)(ms|d|h|m|s)");

    static int uid(String text){
        try{
            if(text.matches("\\d+"))return Integer.parseInt(text);
            Matcher m=Pattern.compile("u(\\d+)([as])(\\d+)").matcher(text);
            if(!m.matches())return -1;
            long n=Long.parseLong(m.group(1))*100000L+Long.parseLong(m.group(3))+("a".equals(m.group(2))?10000:0);
            return n<=Integer.MAX_VALUE?(int)n:-1; // Isolated/sandbox UIDs are intentionally unsupported.
        }catch(Exception e){return -1;}
    }
    static long duration(String value){
        if(value==null)return -1;
        String s=value.trim();if(s.startsWith("+"))s=s.substring(1);if("0".equals(s))return 0;
        Matcher m=DURATION.matcher(s);int end=0;long total=0;
        try{while(m.find()){
            if(m.start()!=end)return -1;end=m.end();String u=m.group(2);
            long scale="d".equals(u)?86400000L:"h".equals(u)?3600000L:"m".equals(u)?60000L:"s".equals(u)?1000L:1L;
            total=Math.addExact(total,Math.multiplyExact(Long.parseLong(m.group(1)),scale));
        }}catch(Exception e){return -1;}
        return end==s.length()&&end>0?total:-1;
    }
    static Snapshot parse(String raw,long captured,TimeZone zone){
        Snapshot out=new Snapshot();int uid=-1,uidIndent=-1,pkgIndent=-1,opIndent=-1;
        String pkg="",op="",tag="";List<Entry> tagAccesses=new ArrayList<>();
        SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT);date.setLenient(false);date.setTimeZone(zone);
        for(String line:raw.split("\\r?\\n")){
            String s=line.trim();int indent=line.length()-line.replaceFirst("^\\s+","").length();
            if("Current AppOps Service state:".equals(s)){out.recognized=true;continue;}
            if(s.startsWith("Historical")||s.startsWith("Discrete")||s.startsWith("AppOps history"))break;
            Matcher u=UID.matcher(s);
            if(u.matches()&&indent==2){uid=uid(u.group(1));uidIndent=indent;pkg="";op="";tagAccesses.clear();continue;}
            if(!s.isEmpty()&&uidIndent>=0&&indent<=uidIndent){uid=-1;uidIndent=-1;pkg="";op="";tagAccesses.clear();}
            Matcher p=PKG.matcher(s);
            if(uid>=0&&p.matches()&&indent==uidIndent+2){pkg=p.group(1);pkgIndent=indent;op="";tagAccesses.clear();continue;}
            if(!s.isEmpty()&&pkgIndent>=0&&indent<=pkgIndent){pkg="";op="";tagAccesses.clear();}
            Matcher o=OP.matcher(s);
            if(!pkg.isEmpty()&&o.matches()&&indent==pkgIndent+2){op=o.group(1);opIndent=indent;tag="";tagAccesses.clear();continue;}
            if(!s.isEmpty()&&opIndent>=0&&indent<=opIndent){op="";tagAccesses.clear();}
            if(op.isEmpty()||!OPERATIONS.containsKey(op))continue;
            if(s.endsWith("=[")){tag=s.substring(0,s.length()-2);tagAccesses.clear();continue;}
            if(s.startsWith("Access:")||s.startsWith("Reject:")){
                Matcher a=ACCESS.matcher(s);if(!a.matches()){out.malformed++;continue;}
                ParsePosition position=new ParsePosition(0);Date parsed=date.parse(a.group(3),position);
                if(parsed==null||position.getIndex()!=a.group(3).length()||parsed.getTime()>captured+1000){out.malformed++;continue;}
                Entry e=new Entry();e.uid=uid;e.pkg=pkg;e.op=op;e.tag=tag;e.key=a.group(2);e.kind="Access".equals(a.group(1))?"ACCESS":"REJECT";e.at=parsed.getTime();e.end=e.at;
                Matcher proxy=PROXY.matcher(s);if(proxy.find()){e.proxyUid=uid(proxy.group(1));e.proxyPackage=proxy.group(2);}
                int d=s.indexOf(" duration=");if(d>=0){String value=s.substring(d+10).split("\\s",2)[0];long length=duration(value);if(length>=0)e.end=Math.min(captured,e.at+Math.min(length,Math.max(0,captured-e.at)));else out.malformed++;}
                out.entries.add(e);if("ACCESS".equals(e.kind))tagAccesses.add(e);
            }else if(s.startsWith("Running start at:")){
                long length=duration(s.substring("Running start at:".length()));
                if(length<0){out.malformed++;continue;}
                long began=Math.max(1,captured-length);
                // The running line belongs to this attribution tag, not to every UID operation.
                Entry nearest=null;for(Entry e:tagAccesses)if(nearest==null||e.at>nearest.at)nearest=e;
                if(nearest!=null){nearest.running=true;nearest.runningSince=began;nearest.end=captured;}
                else{Entry e=new Entry();e.uid=uid;e.pkg=pkg;e.op=op;e.tag=tag;e.kind="ACCESS";e.key="RUNNING";e.at=began;e.end=captured;e.runningSince=began;e.running=true;out.entries.add(e);}
            }
        }
        if(!out.recognized)out.entries.clear();return out;
    }
    static boolean sameActor(int uid,String pkg,String identity,int otherUid,String otherPkg,String otherIdentity,int packages){
        return uid>=0&&uid%100000>=10000&&packages==1&&uid==otherUid&&!pkg.isEmpty()&&pkg.equals(otherPkg)&&!identity.isEmpty()&&identity.equals(otherIdentity);
    }
    static boolean near(long at,long end,long point){return point>0&&at>0&&end>=at&&at<=point+NEAR_MS&&end>=point-NEAR_MS;}
    static boolean fresh(Entry e,long oldAt,long oldEnd,long baseline){
        return e.running?(e.end>oldEnd&&e.end>=baseline):(e.at>oldAt&&e.at>=baseline);
    }
}
