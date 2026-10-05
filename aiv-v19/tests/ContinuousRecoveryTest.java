package fr.erick.journallocal;
import android.content.*;
import android.net.VpnService;
import java.io.File;
import java.util.*;

public class ContinuousRecoveryTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Context fixture(File root,String name,boolean enabled,boolean analysis,boolean vpn,boolean paused){
        RecorderService.running=WatcherService.running=NetworkCaptureService.running=NetworkCaptureService.starting=false;
        VpnService.permitted=true;
        Context c=new Context(new File(root,name));
        Continuous.prefs(c).edit().putBoolean("enabled",enabled).putBoolean("analysis_enabled",analysis).putBoolean("vpn_enabled",vpn).commit();
        c.getSharedPreferences("aiv_storage_guard",0).edit().putBoolean("paused",paused).putString("reason","Plafond local de 2 Gio approché").putLong("paused_at_ms",1000).commit();
        return c;
    }
    public static void main(String[] args){
        File root=new File(args[0]);Context c=fixture(root,"paused",true,true,true,true);
        Continuous.initialize(c);
        check(c.starts.equals(Arrays.asList("RecorderService","WatcherService","NetworkCaptureService")),"legacy pause resumes prior enabled services automatically");
        SharedPreferences legacy=c.getSharedPreferences("aiv_storage_guard",0);
        check(!legacy.getBoolean("paused",true)&&legacy.getLong("paused_at_ms",-1)==0&&legacy.getString("reason","unknown").isEmpty(),"old block cleared durably");
        check(c.notifications.cancellations==1&&!legacy.getBoolean("resume_pending",true),"old notification and recovery marker cleared after start");
        Continuous.initialize(c);check(c.starts.size()==3,"migration not repeated");
        Context reopened=new Context(c.root);Continuous.initialize(reopened);check(reopened.starts.isEmpty(),"ordinary reopen keeps 2.0.11 behavior");

        c=fixture(root,"explicit-stop",false,true,true,true);Continuous.initialize(c);
        check(c.starts.isEmpty()&&!Continuous.enabled(c),"explicit user stop respected");
        check(!c.getSharedPreferences("aiv_storage_guard",0).getBoolean("paused",true),"block removed even when user stopped");
        c=fixture(root,"no-vpn",true,false,false,true);Continuous.initialize(c);
        check(c.starts.equals(Collections.singletonList("RecorderService")),"VPN and analysis left disabled per user settings");
        check(!Continuous.prefs(c).getBoolean("vpn_enabled",true)&&!Continuous.prefs(c).getBoolean("analysis_enabled",true),"no preference overwritten");
        c=fixture(root,"authorization",true,true,true,true);VpnService.permitted=false;Continuous.initialize(c);
        check(!c.starts.contains("NetworkCaptureService")&&NetworkCaptureService.lastError.contains("Autorisation"),"VPN authorization remains required");

        c=fixture(root,"receiver-restricted",true,false,false,true);c.denyRecorder=true;Continuous.start(c);
        check(c.starts.isEmpty()&&c.getSharedPreferences("aiv_storage_guard",0).getBoolean("resume_pending",false),"package receiver restriction leaves recovery pending");
        c=new Context(c.root);Continuous.initialize(c);
        check(c.starts.equals(Collections.singletonList("RecorderService"))&&!c.getSharedPreferences("aiv_storage_guard",0).getBoolean("resume_pending",true),"opening app retries failed recovery");

        c=fixture(root,"asynchronous",true,true,true,true);c.activateImmediately=false;Continuous.start(c);
        check(c.getSharedPreferences("aiv_storage_guard",0).getBoolean("resume_pending",false),"asynchronous start remains pending until collector runs");
        RecorderService.running=WatcherService.running=NetworkCaptureService.running=true;
        Continuous.initialize(c);check(c.starts.size()==3&&!c.getSharedPreferences("aiv_storage_guard",0).getBoolean("resume_pending",true),"running collector completes recovery without duplicate starts");

        c=fixture(root,"stop-pending",true,false,false,true);c.denyRecorder=true;Continuous.start(c);Continuous.stop(c);Continuous.initialize(c);
        check(!Continuous.enabled(c)&&!c.getSharedPreferences("aiv_storage_guard",0).getBoolean("resume_pending",true)&&c.starts.isEmpty(),"user stop cancels pending recovery");
        c=fixture(root,"notify-failure",true,false,false,true);c.notifications.fail=true;Continuous.initialize(c);
        check(c.starts.equals(Collections.singletonList("RecorderService")),"notification failure does not prevent recovery");
        c=fixture(root,"no-migration",true,true,true,false);Continuous.initialize(c);
        check(c.starts.isEmpty(),"2.0.11 users get unchanged initialization behavior");
        System.out.println("PASS "+checks+" recovery assertions: durable 2.0.12 pause removal, automatic restart, user stops/settings, receiver restrictions and asynchronous startup");
    }
}
