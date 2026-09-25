package fr.erick.journallocal;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** Signed JSONL: exact UTF-8 header+rows (including LF) are the authenticated body. */
public final class ExportSigner {
    public static byte[] signingBytes(String hash){return ("AIV-EXPORT-1\n"+hash).getBytes(StandardCharsets.US_ASCII);}
    public static File prepare(Context context)throws Exception{
        PinVault vault=null;String degraded="";
        try{vault=new PinVault();}catch(Exception e){degraded=e.getClass().getSimpleName();}
        File dir=new File(context.getCacheDir(),"exports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cache indisponible");
        File part=File.createTempFile("aiv-",".part",dir),ready=new File(dir,part.getName()+".jsonl");boolean success=false;
        try{
            MessageDigest digest=ChainStore.digest();
            try(FileOutputStream file=new FileOutputStream(part);BufferedOutputStream out=new BufferedOutputStream(file)){
                SQLiteDatabase db=EventStore.get(context).getWritableDatabase();db.beginTransaction();try{
                    if(AivStore.number(db,"SELECT EXISTS(SELECT 1 FROM events WHERE id>(SELECT checkpoint FROM aiv_state WHERE id=1))")>0)throw new IOException("Chaînage en cours; activer AIV et attendre la fin de la reprise");
                    JSONObject verification=AivStore.verify(context);if(!verification.getBoolean("valid"))throw new IOException("Chaîne brisée; export signé refusé, journal brut toujours disponible");
                    JSONObject header=EventStore.object("type","HEADER","format","AIV-EXPORT-1","created_ms",System.currentTimeMillis(),"genesis",ChainStore.GENESIS,"count",verification.getLong("count"),"head",verification.getString("head"),"checkpoint",AivStore.number(db,"SELECT checkpoint FROM aiv_state WHERE id=1"),"signature_status",vault==null?"UNSIGNED_KEYSTORE_UNAVAILABLE":"SIGNED","keystore_error",degraded,"key_id",vault==null?JSONObject.NULL:vault.keyId(),"public_key_spki",vault==null?JSONObject.NULL:Base64.getEncoder().encodeToString(vault.publicKey().getEncoded()),"key_security",vault==null?"UNAVAILABLE":vault.securityLevel);
                    write(out,digest,header.toString());
                    try(Cursor c=db.rawQuery("SELECT * FROM journal_chain ORDER BY id",null)){while(c.moveToNext())write(out,digest,AivStore.row(c).put("type","RECORD").toString());}
                }finally{db.endTransaction();}
                String hash=ChainStore.hex(digest.digest());byte[] signature=vault==null?null:vault.sign(signingBytes(hash));
                if(vault!=null&&!PinVault.verify(vault.publicKey(),signingBytes(hash),signature))throw new GeneralSecurityException("Autovérification échouée");
                JSONObject footer=EventStore.object("type","FOOTER","sha256",hash,"algorithm",vault==null?"NONE":"SHA256withECDSA","signature",signature==null?JSONObject.NULL:Base64.getEncoder().encodeToString(signature));
                out.write((footer.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.flush();file.getFD().sync();
            }
            if(!part.renameTo(ready))throw new IOException("Publication locale de l’export impossible");success=true;return ready;
        }finally{if(!success)part.delete();}
    }
    private static void write(OutputStream out,MessageDigest digest,String json)throws IOException{byte[] bytes=(json+"\n").getBytes(StandardCharsets.UTF_8);digest.update(bytes);out.write(bytes);}
}