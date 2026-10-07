package fr.erick.journallocal;

import java.io.*;
import java.util.concurrent.CountDownLatch;
import rikka.shizuku.Shizuku;

public final class ControlShellTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final class Remote extends Process {
        final InputStream stdout,stderr;final CountDownLatch release=new CountDownLatch(1);
        final boolean blocked;volatile boolean destroyed;volatile int exitCalls;
        Remote(byte[] out,InputStream err,boolean blocked){stdout=new ByteArrayInputStream(out);stderr=err;this.blocked=blocked;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return stdout;}
        public InputStream getErrorStream(){return stderr;}
        public int waitFor()throws InterruptedException{if(blocked)release.await();return 0;}
        public int exitValue(){exitCalls++;throw new IllegalArgumentException("process hasn't exited");}
        public void destroy(){destroyed=true;release.countDown();}
    }
    static InputStream empty(){return new ByteArrayInputStream(new byte[0]);}
    public static void main(String[] args)throws Exception{
        ProductAccess.enabled=false;int blockedCalls=Shizuku.calls;
        try{ControlShell.run("cmd appops set example.app CAMERA deny");throw new AssertionError("FREE mutation permitted");}catch(SecurityException expected){}
        try{ControlShell.readAppOps(null,0,1024);throw new AssertionError("FREE Shizuku read permitted");}catch(SecurityException expected){}
        check(Shizuku.calls==blockedCalls,"FREE spawns no Shizuku process");ProductAccess.enabled=true;
        Remote normal=new Remote("flags verified".getBytes("UTF-8"),empty(),false);Shizuku.process=normal;
        ControlShell.Result result=ControlShell.run("dumpsys package com.example.app",1024);
        check(result.code==0&&result.complete&&result.out.equals("flags verified"),"Completed remote process returns complete stdout");
        check(normal.exitCalls==0,"Regression: do not poll RemoteProcess.exitValue()");
        check(normal.destroyed,"Process handles are released");
        Shizuku.process=new Remote("No operations.".getBytes("UTF-8"),empty(),false);ControlShell.readAppOps("example.chat",0,65536);
        check(java.util.Arrays.equals(Shizuku.argv,new String[]{"/system/bin/cmd","appops","get","--user","0","example.chat"}),"read executes fixed argv, no shell");
        Shizuku.process=new Remote(new byte[0],empty(),false);ControlShell.readAppOps(null,0,1024);check(Shizuku.argv[0].equals("/system/bin/dumpsys")&&Shizuku.argv.length==2,"global read shell introduced");
        check(AccessPolicy.allows("appops.observe",1)&&!AccessPolicy.allows("shizuku.control",1),"free observation gates mutation");
        int before=Shizuku.calls;boolean injection=false;try{ControlShell.readAppOps("example.chat; appops set x CAMERA deny",0,1024);}catch(IllegalArgumentException refusedPackage){injection=true;}check(injection&&Shizuku.calls==before,"arbitrary shell passed reader");
        Remote large=new Remote(new byte[2048],empty(),false);Shizuku.process=large;result=ControlShell.run("read",1024);
        check(result.out.length()==1024&&!result.complete,"Truncated evidence cannot authorize permission changes");
        Remote broken=new Remote(new byte[0],new InputStream(){public int read()throws IOException{throw new IOException("remote pipe lost");}},false);
        Shizuku.process=broken;result=ControlShell.run("read");
        check(!result.complete,"Reader errors cannot masquerade as a verified empty observation");
        int calls=Shizuku.calls;Shizuku.permission=-1;boolean refused=false;
        try{ControlShell.run("read");}catch(IllegalStateException e){refused=true;}finally{Shizuku.permission=0;}
        check(refused&&Shizuku.calls==calls,"Unauthorized Shizuku never spawns a process");
        Remote hanging=new Remote(new byte[0],empty(),true);Shizuku.process=hanging;boolean timedOut=false;
        long start=System.nanoTime();try{ControlShell.run("hang");}catch(IOException e){timedOut=e.getMessage().contains("Délai");}
        check(timedOut&&hanging.destroyed&&hanging.exitCalls==0,"Timeout destroys the remote process without exitValue polling");
        check((System.nanoTime()-start)/1000000<AivConfig.CONTROL_COMMAND_TIMEOUT_MS+4000,"Timeout stays bounded");
        System.out.println("ControlShell: remote completion, truncation, pipe failure, authorization and timeout passed");
    }
}
