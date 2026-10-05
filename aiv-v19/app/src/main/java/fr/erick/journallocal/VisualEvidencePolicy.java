package fr.erick.journallocal;
import java.io.*;
import java.util.*;
/** Retained files exist only for committed findings; temporary frames stay in RAM. */
final class VisualEvidencePolicy {
    static final long RETENTION_MS=7L*86400000,MAX_BYTES=20L*1024*1024;
    static final int MAX_FILES=100;
    static boolean retain(long findingId,boolean sameWindow,boolean fresh){return findingId>0&&sameWindow&&fresh;}
    static String failure(int code){switch(code){case 6:return "SECURE_WINDOW";case 5:return "INVALID_WINDOW";case 4:return "INVALID_DISPLAY";case 3:return "ANDROID_RATE_LIMIT";case 2:return "NO_ACCESSIBILITY_ACCESS";default:return "ANDROID_INTERNAL_ERROR_"+code;}}
    static void prune(File directory,long now,long retentionMs,long maxBytes,int maxFiles){
        File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());long total=0;int kept=0;
        for(File f:files){if(!f.isFile())continue;total+=f.length();if(now-f.lastModified()>retentionMs||++kept>maxFiles||total>maxBytes)f.delete();}
    }
}
