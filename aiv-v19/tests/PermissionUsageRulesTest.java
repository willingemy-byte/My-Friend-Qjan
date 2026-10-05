package fr.erick.journallocal;
import java.util.*;
import java.text.*;

public class PermissionUsageRulesTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static String fixture(String operation,String rows){
        // AOSP AppOpsService current-state structure, synthetic identities/dates only.
        return "Current AppOps Service state:\n  Uid u0a371:\n    state=top\n    Package example.chat:\n      "+operation+" (allow):\n        null=[\n"+rows+"        ]\n";
    }
    public static void main(String[] args)throws Exception{
        TimeZone utc=TimeZone.getTimeZone("UTC");SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.ROOT);f.setTimeZone(utc);
        long now=f.parse("2026-10-05 01:20:00.000").getTime();
        String access="          Access: [top-s] 2026-10-05 01:19:40.000 (-20s0ms)\n";
        PermissionUsageRules.Snapshot s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access),now,utc);
        check(s.recognized&&s.entries.size()==1,"successful read missing");
        PermissionUsageRules.Entry e=s.entries.get(0);
        check(e.uid==10371&&e.pkg.equals("example.chat")&&e.op.equals("READ_CONTACTS")&&e.kind.equals("ACCESS"),"wrong actor/operation");
        check(e.at==now-20000&&!e.running&&e.end==e.at,"wrong read timestamp");
        check(PermissionUsageRules.OPERATIONS.get(e.op).permission.equals("android.permission.READ_CONTACTS"),"permission mapping");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS","          Reject: [bg-s] 2026-10-05 01:19:55.000 (-5s0ms)\n"),now,utc);
        check(s.entries.size()==1&&s.entries.get(0).kind.equals("REJECT"),"rejected attempt reported as successful");
        s=PermissionUsageRules.parse(fixture("RECORD_AUDIO",access+"          Running start at: +20s0ms\n"),now,utc);
        check(s.entries.get(0).running&&s.entries.get(0).end==now,"ongoing mic missing");
        s=PermissionUsageRules.parse(fixture("RECORD_AUDIO",access.replace("2026-10-05","2026-10-04")+"          Running start at: +10s0ms\n"),now,utc);
        check(s.entries.get(0).runningSince==now-10000&&s.entries.get(0).at<now-86400000,"old last-access mistaken for running start");
        s=PermissionUsageRules.parse(fixture("RECORD_AUDIO",access.replace("(-20s0ms)","(-20s0ms) duration=+3s200ms proxy[uid=1000, pkg=android, attributionTag=null]")),now,utc);
        check(s.entries.get(0).end==now-16800&&s.entries.get(0).proxyUid==1000&&s.entries.get(0).proxyPackage.equals("android"),"duration/proxy lost");
        String second="    Package example.camera:\n      CAMERA (foreground / switch CAMERA=foreground):\n        capture=[\n"+access+"        ]\n";
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access)+second,now,utc);
        check(s.entries.size()==2&&s.entries.get(1).pkg.equals("example.camera")&&s.entries.get(1).tag.equals("capture"),"cross-package/tag bleed");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access)+"  Historical AppOps:\n"+fixture("CAMERA",access).replace("Current AppOps Service state:\n",""),now,utc);
        check(s.entries.size()==1,"aggregate history mistaken for current state");
        s=PermissionUsageRules.parse("Permission Denial: can't dump AppOps",now,utc);check(!s.recognized&&s.entries.isEmpty(),"denial accepted");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access.replace("2026-10-05","2027-10-05")),now,utc);check(s.entries.isEmpty()&&s.malformed==1,"future access accepted");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access.replace("2026-10-05","2026-99-99")),now,utc);check(s.entries.isEmpty()&&s.malformed==1,"invalid date accepted");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access.replace("Access:","Access:").replace("[top-s] ","")),now,utc);check(s.entries.isEmpty()&&s.malformed==1,"unknown time format accepted");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access).replace("u0a371","u10a371"),now,utc);check(s.entries.get(0).uid==1010371,"work profile collapsed");
        s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access).replace("u0a371","u0i371"),now,utc);check(s.entries.isEmpty(),"isolated UID guessed");
        check(PermissionUsageRules.uid("1000")==1000&&PermissionUsageRules.uid("u0s1000")==1000&&PermissionUsageRules.uid("u0a99999999999999")==-1,"UID parsing");
        check(PermissionUsageRules.duration("+2d3h4m5s6ms")==183845006&&PermissionUsageRules.duration("-3s")==-1&&PermissionUsageRules.duration("1sBAD")==-1,"duration parsing");
        check(PermissionUsageRules.sameActor(10371,"example.chat","cert",10371,"example.chat","cert",1),"same actor rejected");
        check(!PermissionUsageRules.sameActor(10371,"example.chat","cert",10372,"example.chat","cert",1),"wrong UID linked");
        check(!PermissionUsageRules.sameActor(10371,"example.chat","cert",10371,"example.chat","other",1),"different signer linked");
        check(!PermissionUsageRules.sameActor(10371,"example.chat","cert",10371,"example.chat","cert",2),"shared UID linked");
        check(!PermissionUsageRules.sameActor(1000,"android","cert",1000,"android","cert",1),"system UID linked");
        check(!PermissionUsageRules.sameActor(10371,"example.chat","",10371,"example.chat","",1),"missing identity guessed");
        check(PermissionUsageRules.near(now-20000,now-20000,now)&&!PermissionUsageRules.near(now-60001,now-60001,now),"window bounds");
        check(PermissionUsageRules.near(now-3600000,now,now-10000),"ongoing interval missing");
        e=safeEntry(now-20000);check(!PermissionUsageRules.fresh(e,0,0,now),"initial history replayed as new access");
        check(PermissionUsageRules.fresh(e,0,0,now-30000)&&!PermissionUsageRules.fresh(e,e.at,e.end,now-30000),"dedup loses/multiplies access");
        e.running=true;e.end=now;check(PermissionUsageRules.fresh(e,e.at,now-60000,now)&&!PermissionUsageRules.fresh(e,e.at,now,now),"ongoing snapshot dedup");
        TimeZone montreal=TimeZone.getTimeZone("America/Toronto");s=PermissionUsageRules.parse(fixture("READ_CONTACTS",access.replace("01:19:40","21:19:40").replace("2026-10-05","2026-10-04")),now,montreal);check(s.entries.get(0).at==now-20000,"device timezone ignored");
        s=PermissionUsageRules.parsePackage("Uid mode: READ_CONTACTS: allow\nREAD_CONTACTS: allow; time=+1s200ms ago; rejectTime=+5s ago; duration=+100ms\nCAMERA: foreground; time=0 ago (running)\nUNKNOWN_OP: allow\n", "example.chat",10371,now);
        check(s.recognized&&s.entries.size()==3,"package output or UID-mode boundary lost");check(s.entries.get(0).at==now-1200&&s.entries.get(0).durationMs==100&&s.entries.get(0).mode.equals("allow"),"relative access/mode/duration wrong");check(s.entries.get(1).kind.equals("REJECT")&&s.entries.get(1).at==now-5000,"relative rejection lost");check(s.entries.get(2).running&&s.entries.get(2).end==now,"relative running lost");check(s.unknown.size()==1,"unknown row lost or UID capability falsely partial");
        s=PermissionUsageRules.parsePackage("READ_CONTACTS: allow\n", "example.chat",10371,now);check(s.recognized&&s.entries.isEmpty(),"mode-only grant became activity");
        check(PermissionUsageRules.parsePackage("No operations.","example.chat",10371,now).recognized,"empty successful read not recognized");check(!PermissionUsageRules.parsePackage("Permission Denial", "example.chat",10371,now).recognized,"refused package accepted");
        s=PermissionUsageRules.parsePackage("READ_CONTACTS: allow; time=bad ago\n", "example.chat",10371,now);check(s.malformed==1&&s.entries.isEmpty(),"partial output fabricated time");check(!PermissionUsageRules.parsePackage("UNKNOWN_OP: allow", "example.chat",10371,now).recognized,"unknown op overclaimed");
        s=PermissionUsageRules.parsePackage("Uid mode: COARSE_LOCATION: ignore\nCALL_PHONE: ignore\nRECEIVE_MMS: ignore\nRECEIVE_WAP_PUSH: ignore\nADD_VOICEMAIL: ignore\nUSE_SIP: ignore\nPROCESS_OUTGOING_CALLS: ignore\nBODY_SENSORS: ignore\n", "example.chat",10371,now);check(s.recognized&&s.entries.isEmpty()&&s.unknown.isEmpty(),"valid capability rows falsely partial or became accesses");
        s=PermissionUsageRules.parsePackage("Uid mode: READ_CONTACTS: allow; time=+1s ago\n", "example.chat",10371,now);check(s.entries.isEmpty()&&!s.unknown.isEmpty(),"UID capability became package activity");
        check(PermissionUsageRules.activity(false,false).equals("CAPABILITY_ONLY")&&PermissionUsageRules.activity(false,true).equals("OBSERVED_RECENT")&&PermissionUsageRules.activity(true,true).equals("OBSERVED_RUNNING"),"capability/activity states wrong");
        System.out.println("PermissionUsageRulesTest: "+checks+" checks passed");
    }
    private static PermissionUsageRules.Entry safeEntry(long at){PermissionUsageRules.Entry e=new PermissionUsageRules.Entry();e.at=at;e.end=at;return e;}
}
