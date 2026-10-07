package fr.erick.journallocal;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import rikka.shizuku.Shizuku;
import android.content.pm.PackageManager;

/**
 * Executes a Shizuku-backed shell command without calling Process.exitValue()
 * before the remote process has actually finished.
 *
 * Some Shizuku RemoteProcess implementations throw IllegalArgumentException
 * ("process hasn't exited") from exitValue(). The default Java timed waitFor()
 * can poll exitValue(), so AIV waits on a dedicated waiter thread instead.
 * stdout/stderr are drained concurrently to avoid pipe deadlocks.
 */
final class ControlShell {
    static final class Result {
        final int code;final String out,err;
        final boolean complete;
        Result(int code,String out,String err,boolean complete){this.code=code;this.out=out;this.err=err;this.complete=complete;}
    }

    static Result run(String command)throws Exception{
        return run(command,AivConfig.CONTROL_OUTPUT_MAX_BYTES);
    }

    static Result run(String command,int outputLimit)throws Exception{
        if(outputLimit<1||outputLimit>1024*1024)throw new IllegalArgumentException("Limite de sortie invalide");
        if(!AccessPolicy.allows("shizuku.control",ProductAccess.verifiedTier()))
            throw new SecurityException("Contrôle indisponible dans cette édition");
        return execute(new String[]{"/system/bin/sh","-c",command},outputLimit);
    }

    /** Observation capability: fixed argv, no shell and no permission mutation. */
    static Result readAppOps(String pkg,int user,int outputLimit)throws Exception{
        if(!AccessPolicy.allows("appops.observe",ProductAccess.verifiedTier()))throw new SecurityException("Observation indisponible");
        if(user<0||user>21474)throw new IllegalArgumentException("Profil invalide");
        if(pkg==null)return execute(new String[]{"/system/bin/dumpsys","appops"},outputLimit);
        if(!pkg.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+"))throw new IllegalArgumentException("Paquet invalide");
        return execute(new String[]{"/system/bin/cmd","appops","get","--user",String.valueOf(user),pkg},outputLimit);
    }

    private static Result execute(String[] argv,int outputLimit)throws Exception{
        ProductAccess.requireFounder();
        if(outputLimit<1||outputLimit>1024*1024)throw new IllegalArgumentException("Limite de sortie invalide");
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Shizuku arrêté ou non autorisé");

        Method method=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);
        method.setAccessible(true);
        Process process=(Process)method.invoke(null,new Object[]{argv,null,null});

        Collector out=new Collector(process.getInputStream(),outputLimit),err=new Collector(process.getErrorStream(),AivConfig.CONTROL_OUTPUT_MAX_BYTES);
        Thread stdout=new Thread(out,"aiv-control-stdout"),stderr=new Thread(err,"aiv-control-stderr");
        stdout.setDaemon(true);stderr.setDaemon(true);stdout.start();stderr.start();

        AtomicInteger exitCode=new AtomicInteger(Integer.MIN_VALUE);
        AtomicReference<Throwable> waitError=new AtomicReference<>();
        Thread waiter=new Thread(()->{
            try{exitCode.set(process.waitFor());}
            catch(Throwable t){waitError.set(t);}
        },"aiv-control-waiter");
        waiter.setDaemon(true);
        waiter.start();

        try{
            waiter.join(AivConfig.CONTROL_COMMAND_TIMEOUT_MS);
            if(waiter.isAlive()){
                process.destroy();
                waiter.join(1000);
                throw new IOException("Délai dépassé (12 secondes), état à vérifier");
            }

            Throwable failure=waitError.get();
            if(failure!=null){
                if(failure instanceof Exception)throw (Exception)failure;
                throw new IOException("Échec d'attente du processus Shizuku",failure);
            }

            stdout.join(1000);stderr.join(1000);
            int code=exitCode.get();
            if(code==Integer.MIN_VALUE)throw new IOException("Processus Shizuku terminé sans code de sortie");
            return new Result(code,out.text(),err.text(),out.complete()&&err.complete());
        }finally{
            try{process.destroy();}catch(Throwable ignored){}
            try{process.getInputStream().close();}catch(IOException ignored){}
            try{process.getErrorStream().close();}catch(IOException ignored){}
            try{process.getOutputStream().close();}catch(IOException ignored){}
        }
    }

    private static final class Collector implements Runnable{
        private final InputStream in;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private volatile boolean done,truncated,failed;
        Collector(InputStream in,int limit){this.in=in;this.limit=limit;}
        public void run(){
            try{
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){
                    synchronized(bytes){
                        int keep=Math.min(n,limit-bytes.size());
                        if(keep<n)truncated=true;
                        if(keep>0)bytes.write(buffer,0,keep);
                    }
                }
            }catch(IOException ignored){failed=true;}finally{done=true;}
        }
        String text(){synchronized(bytes){return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}}
        boolean complete(){return done&&!failed&&!truncated;}
    }
}
