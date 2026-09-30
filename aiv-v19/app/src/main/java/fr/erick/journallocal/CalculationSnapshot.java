package fr.erick.journallocal;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;
/** Disposable derived results; raw evidence and user configuration are never stored here. */
public final class CalculationSnapshot {
    private static String hash(String s)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder h=new StringBuilder();for(byte v:bytes)h.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return h.toString();}
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"penalty-result-snapshot.json"));}
    public static synchronized JSONObject load(Context c,String identity)throws Exception{
        if(identity.length()>32*1024*1024)throw new IOException("Instantané trop volumineux");
        try{JSONObject v=new JSONObject(new String(file(c).readFully(),StandardCharsets.UTF_8));String result=v.getString("result");
            if(hash(identity).equals(v.optString("key"))&&hash(result).equals(v.optString("checksum")))return EventStore.object("cached",true,"result",new JSONObject(result));
        }catch(Exception ignored){/* A missing or damaged derived snapshot is recomputed. */}
        return EventStore.object("cached",false);
    }
    public static synchronized JSONObject save(Context c,String identity,String result)throws Exception{
        if(identity.length()>32*1024*1024||result.length()>32*1024*1024)throw new IOException("Instantané trop volumineux");
        if(!"aiv-penalty-result/2".equals(new JSONObject(result).optString("schema")))throw new IOException("Résultat inconnu");
        byte[] bytes=EventStore.object("key",hash(identity),"checksum",hash(result),"result",result).toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile f=file(c);FileOutputStream stream=null;try{stream=f.startWrite();stream.write(bytes);f.finishWrite(stream);}catch(Exception e){if(stream!=null)f.failWrite(stream);throw e;}
        return EventStore.object("ok",true);
    }
}