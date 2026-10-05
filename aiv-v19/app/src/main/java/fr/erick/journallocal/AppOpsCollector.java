package fr.erick.journallocal;

/** Strict read-only transport shared with the existing Shizuku process implementation. */
final class AppOpsCollector {
    static ControlShell.Result readAll()throws Exception{return ControlShell.readAppOps(null,0,1024*1024);}
    static ControlShell.Result readPackage(String pkg,int user)throws Exception{return ControlShell.readAppOps(pkg,user,65536);}
    static boolean complete(ControlShell.Result r){return r.code==0&&r.complete&&r.err.trim().isEmpty();}
}
