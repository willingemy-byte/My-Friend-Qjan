package rikka.shizuku;
/** Host-test remote process only; excluded from application source/build. */
public final class Shizuku {
    public static Process process;
    public static boolean connected=true;
    public static int permission=0,calls;
    public static boolean pingBinder(){return connected;}
    public static int checkSelfPermission(){return permission;}
    public static Process newProcess(String[] command,String[] environment,String directory){calls++;return process;}
}
