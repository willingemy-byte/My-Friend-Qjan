package fr.erick.journallocal;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;

/**
 * Persisted Android reconciliation job for sealed archive segments.
 *
 * The project uses the platform JobScheduler instead of adding the AndroidX
 * WorkManager dependency tree to the custom javac/aapt2 build. It provides the
 * required durable "network available -> reconcile -> retry" behavior.
 */
public final class ArchiveReconcileJob extends JobService {
    private static final int JOB_ID=22301;
    private volatile boolean stopped;

    public static void schedule(Context context){
        try{
            JobScheduler js=context.getSystemService(JobScheduler.class);
            if(js==null)return;
            JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(context,ArchiveReconcileJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
            js.schedule(info);
        }catch(Exception ignored){}
    }

    public static void cancel(Context context){
        try{JobScheduler js=context.getSystemService(JobScheduler.class);if(js!=null)js.cancel(JOB_ID);}catch(Exception ignored){}
    }

    @Override public boolean onStartJob(JobParameters params){
        stopped=false;
        new Thread(()->run(params),"aiv-archive-reconcile-job").start();
        return true;
    }

    private void run(JobParameters params){
        boolean retry=false;
        try{
            ArchiveSync.requestAutomatic(this);
            JournalPurge.request(this);
            // Keep the OS job alive while the existing bounded workers run.
            for(int i=0;i<180&&!stopped;i++){
                Thread.sleep(1000L);
                JSONObject archive=ArchiveSync.state(this),purge=JournalPurge.state(this);
                if(!archive.optBoolean("running")&&!purge.optBoolean("running")){
                    long pending=archive.optLong("pending_segments",0);
                    String error=archive.optString("last_error","");
                    retry=pending>0&&!error.isEmpty();
                    break;
                }
                if(i==179)retry=true;
            }
        }catch(Exception e){retry=true;}
        if(!stopped)jobFinished(params,retry);
    }

    @Override public boolean onStopJob(JobParameters params){
        stopped=true;
        return true;
    }
}
