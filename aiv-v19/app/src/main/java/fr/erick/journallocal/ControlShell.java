package fr.erick.journallocal;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;
import android.content.pm.PackageManager;

/** Concurrent pipe draining and a deadline prevent a refused command hanging the UI. */
final class ControlShell {
    static final class Result {
        final int code;final String out,err;
        Result(int code,String out,String err){this.code=code;this.out=out;this.err=err;}
    }
    static Result run(String command)throws Exception{
        if(!AccessPolicy.allows("shizuku.control",AccessPolicy.DISTRIBUTION_TIER))throw new SecurityException("Contrôle indisponible dans cette édition");
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Shizuku arrêté ou non autorisé");
        Method method=Shizuku.class.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);method.setAccessible(true);
        Process process=(Process)method.invoke(null,new Object[]{new String[]{"/system/bin/sh","-c",command},null,null});
        Collector out=new Collector(process.getInputStream()),err=new Collector(process.getErrorStream());
        Thread a=new Thread(out,"aiv-control-stdout"),b=new Thread(err,"aiv-control-stderr");a.setDaemon(true);b.setDaemon(true);a.start();b.start();
        try{
            if(!process.waitFor(AivConfig.CONTROL_COMMAND_TIMEOUT_MS,TimeUnit.MILLISECONDS)){process.destroy();throw new IOException("Délai dépassé (12 secondes), état à vérifier");}
            a.join(1000);b.join(1000);
            return new Result(process.exitValue(),out.text(),err.text());
        }finally{process.destroy();try{process.getInputStream().close();}catch(IOException ignored){}try{process.getErrorStream().close();}catch(IOException ignored){}}
    }
    private static final class Collector implements Runnable{
        private final InputStream in;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Collector(InputStream in){this.in=in;}
        public void run(){try{byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){synchronized(bytes){int keep=Math.min(n,AivConfig.CONTROL_OUTPUT_MAX_BYTES-bytes.size());if(keep>0)bytes.write(buffer,0,keep);}}}catch(IOException ignored){}}
        String text(){synchronized(bytes){return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}}
    }
}
