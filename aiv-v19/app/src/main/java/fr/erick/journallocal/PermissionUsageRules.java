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
        int uid,proxyUid=-1;String pkg,op,tag="",key="",kind,proxyPackage="",mode="",source="DUMPSYS_APPOPS";
        long at,end,runningSince,durationMs=-1,uncertaintyMs;boolean running;
        String dimension(){return uid+"|"+pkg+"|"+op+"|"+tag+"|"+key+"|"+kind;}
    }
    static final class Snapshot {
        final List<Entry> entries=new ArrayList<>();final List<String> unknown=new ArrayList<>();boolean recognized;int malformed;
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
        String pkg="",op="",tag="",mode="";List<Entry> tagAccesses=new ArrayList<>();
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
            if(!pkg.isEmpty()&&o.matches()&&indent==pkgIndent+2){op=o.group(1);mode=s.substring(s.indexOf('(')+1,s.indexOf(')'));opIndent=indent;tag="";tagAccesses.clear();continue;}
            if(!s.isEmpty()&&opIndent>=0&&indent<=opIndent){op="";tagAccesses.clear();}
            if(op.isEmpty()||!OPERATIONS.containsKey(op))continue;
            if(s.endsWith("=[")){tag=s.substring(0,s.length()-2);tagAccesses.clear();continue;}
            if(s.startsWith("Access:")||s.startsWith("Reject:")){
                Matcher a=ACCESS.matcher(s);if(!a.matches()){out.malformed++;unknown(out,s);continue;}
                ParsePosition position=new ParsePosition(0);Date parsed=date.parse(a.group(3),position);
                if(parsed==null||position.getIndex()!=a.group(3).length()||parsed.getTime()>captured+1000){out.malformed++;unknown(out,s);continue;}
                Entry e=new Entry();e.uid=uid;e.pkg=pkg;e.op=op;e.tag=tag;e.key=a.group(2);e.kind="Access".equals(a.group(1))?"ACCESS":"REJECT";e.at=parsed.getTime();e.end=e.at;
                Matcher proxy=PROXY.matcher(s);if(proxy.find()){e.proxyUid=uid(proxy.group(1));e.proxyPackage=proxy.group(2);}
                int d=s.indexOf(" duration=");if(d>=0){String value=s.substring(d+10).split("\\s",2)[0];long length=duration(value);if(length>=0){e.durationMs=length;e.end=Math.min(captured,e.at+Math.min(length,Math.max(0,captured-e.at)));}else out.malformed++;}
                e.mode=mode;out.entries.add(e);if("ACCESS".equals(e.kind))tagAccesses.add(e);
            }else if(s.startsWith("Running start at:")){
                long length=duration(s.substring("Running start at:".length()));
                if(length<0){out.malformed++;continue;}
                long began=Math.max(1,captured-length);
                // The running line belongs to this attribution tag, not to every UID operation.
                Entry nearest=null;for(Entry e:tagAccesses)if(nearest==null||e.at>nearest.at)nearest=e;
                if(nearest!=null){nearest.running=true;nearest.runningSince=began;nearest.end=captured;}
                else{Entry e=new Entry();e.uid=uid;e.pkg=pkg;e.op=op;e.tag=tag;e.mode=mode;e.kind="ACCESS";e.key="RUNNING";e.at=began;e.end=captured;e.runningSince=began;e.running=true;out.entries.add(e);}
            }
        }
        if(!out.recognized)out.entries.clear();return out;
    }
    /** cmd appops get output; times are relative to this read, not exact packet times. */
    static Snapshot parsePackage(String raw,String pkg,int uid,long captured){
        Snapshot out=new Snapshot();String tag="";
        Pattern row=Pattern.compile("(?:android:)?([A-Za-z][A-Za-z_0-9]*):\\s*([a-zA-Z_-]+)(.*)");
        Pattern time=Pattern.compile("(?:^|[; ,])(?:lastAccess|time)=([^; ,]+)(?:\\s+ago)?");
        Pattern reject=Pattern.compile("(?:^|[; ,])rejectTime=([^; ,]+)(?:\\s+ago)?");
        for(String line:raw.split("\\r?\\n")){
            String s=line.trim();if(s.isEmpty())continue;
            if(s.equals("No operations.")||s.equals("No operations")){out.recognized=true;continue;}
            boolean uidMode=s.startsWith("Uid mode:");if(uidMode)s=s.substring(9).trim();
            if(s.startsWith("Attribution tag:")){tag=s.substring(16).trim();continue;}
            Matcher m=row.matcher(s);if(!m.matches()){unknown(out,s);continue;}
            String op=m.group(1).toUpperCase(Locale.ROOT);
            boolean modeOnly=m.group(3).trim().isEmpty()&&Arrays.asList("allow","ignore","deny","default","foreground","errored","ask").contains(m.group(2));
            boolean knownMode=OPERATIONS.containsKey(op)||Arrays.asList("CALL_PHONE","RECEIVE_MMS","RECEIVE_WAP_PUSH","ADD_VOICEMAIL","USE_SIP","PROCESS_OUTGOING_CALLS","BODY_SENSORS").contains(op);
            if(modeOnly&&knownMode){out.recognized=true;continue;}
            if(uidMode||!OPERATIONS.containsKey(op)){unknown(out,s);continue;}
            out.recognized=true;
            String tail=m.group(3);Matcher a=time.matcher(tail),r=reject.matcher(tail);
            boolean access=false;
            if(a.find()){access=true;relative(out,pkg,uid,op,tag,m.group(2),"ACCESS",a.group(1),tail,captured);}
            if(r.find())relative(out,pkg,uid,op,tag,m.group(2),"REJECT",r.group(1),tail,captured);
            if(!access&&(tail.contains("running=true")||tail.contains("(running)"))){Entry e=new Entry();e.source="CMD_APPOPS_GET";e.pkg=pkg;e.uid=uid;e.op=op;e.tag=tag;e.mode=m.group(2);e.kind="ACCESS";e.key="RUNNING";e.at=captured;e.end=captured;e.runningSince=captured;e.running=true;out.entries.add(e);}
            // Mode-only rows intentionally produce no access observation.
        }
        return out;
    }
    private static void relative(Snapshot out,String pkg,int uid,String op,String tag,String mode,String kind,String value,String tail,long captured){
        String v=value.startsWith("-")?value.substring(1):value;long age=duration(v);
        if(age<0||age>captured){out.malformed++;unknown(out,op+": "+tail);return;}
        Entry e=new Entry();e.source="CMD_APPOPS_GET";e.pkg=pkg;e.uid=uid;e.op=op;e.tag=tag;e.mode=mode;e.kind=kind;e.key="PACKAGE_READ";e.at=captured-age;e.end=e.at;
        Matcher d=Pattern.compile("duration=([^; ,]+)").matcher(tail);if(d.find()){long length=duration(d.group(1));if(length>=0){e.durationMs=length;e.end=Math.min(captured,e.at+Math.min(length,age));}}
        e.running="ACCESS".equals(kind)&&(tail.contains("running=true")||tail.contains("(running)")||tail.contains("duration=-1"));if(e.running){e.runningSince=e.at;e.end=captured;}
        out.entries.add(e);
    }
    private static void unknown(Snapshot out,String line){if(out.unknown.size()<8)out.unknown.add(line.substring(0,Math.min(256,line.length())));}
    static String activity(boolean running,boolean access){return running?"OBSERVED_RUNNING":access?"OBSERVED_RECENT":"CAPABILITY_ONLY";}
    static boolean sameActor(int uid,String pkg,String identity,int otherUid,String otherPkg,String otherIdentity,int packages){
        return uid>=0&&uid%100000>=10000&&packages==1&&uid==otherUid&&!pkg.isEmpty()&&pkg.equals(otherPkg)&&!identity.isEmpty()&&identity.equals(otherIdentity);
    }
    static boolean near(long at,long end,long point){return point>0&&at>0&&end>=at&&at<=point+NEAR_MS&&end>=point-NEAR_MS;}
    static boolean fresh(Entry e,long oldAt,long oldEnd,long baseline){
        return e.running?(e.end>oldEnd&&e.end>=baseline):(e.at>oldAt&&e.at>=baseline);
    }
}
