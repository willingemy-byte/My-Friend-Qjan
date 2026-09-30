package fr.erick.journallocal;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
/** Complete a private snapshot before opening the chosen document for writing. */
final class ExportFiles {
    interface Write {void write(Writer writer)throws Exception;}
    static File stage(Context context,Write operation)throws Exception{
        File dir=new File(context.getCacheDir(),"exports");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cache indisponible");File[] old=dir.listFiles();if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>7L*86400000)f.delete();File temp=File.createTempFile("snapshot-",".partial",dir);
        try{try(FileOutputStream bytes=new FileOutputStream(temp);Writer writer=new BufferedWriter(new OutputStreamWriter(bytes,StandardCharsets.UTF_8))){operation.write(writer);writer.flush();bytes.getFD().sync();}File ready=new File(dir,temp.getName().replace(".partial",".ready"));if(!temp.renameTo(ready))throw new IOException("Finalisation locale impossible");return ready;}catch(Exception e){temp.delete();throw e;}
    }
    static String copy(Context c,File ready,Uri destination)throws Exception{
        MessageDigest expected=MessageDigest.getInstance("SHA-256");long size=0;
        try(InputStream in=new FileInputStream(ready);OutputStream out=c.getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("Destination indisponible");byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){out.write(b,0,n);expected.update(b,0,n);size+=n;}out.flush();}
        String hash=JournalRecovery.hex(expected.digest());InputStream verify;
        try{verify=c.getContentResolver().openInputStream(destination);}catch(Exception e){return "Copie écrite; relecture indisponible. Instantané complet conservé temporairement dans l’application.";}
        if(verify==null)return "Copie écrite; relecture indisponible.";MessageDigest actual=MessageDigest.getInstance("SHA-256");long copied=0;
        try(InputStream in=verify){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){actual.update(b,0,n);copied+=n;if(copied>size)break;}}
        if(copied!=size||!hash.equals(JournalRecovery.hex(actual.digest())))throw new IOException("La copie ne correspond pas à l’instantané complet; recommencer l’export");return "Export terminé et copie vérifiée.";
    }
    static File recover(Context c,Uri source,JSONObject[] report)throws Exception{
        MessageDigest original=MessageDigest.getInstance("SHA-256");return stage(c,writer->{
            writer.write("{\"schema\":\"journal-recovered/1\",\"source\":\"Copie récupérée; distincte du journal en cours\",\"events\":[\n");final boolean[] first={true};
            try(InputStream raw=c.getContentResolver().openInputStream(source)){
                if(raw==null)throw new IOException("Source indisponible");InputStream limited=new FilterInputStream(new java.security.DigestInputStream(raw,original)){
                    long read=0;@Override public int read(byte[] b,int off,int len)throws IOException{int n=super.read(b,off,len);if(n>0&&(read+=n)>128L*1024*1024)throw new IOException("Source de plus de 128 Mio");return n;}
                    @Override public int read()throws IOException{int n=super.read();if(n>=0&&++read>128L*1024*1024)throw new IOException("Source de plus de 128 Mio");return n;}};
                java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                report[0]=JournalRecovery.recover(new InputStreamReader(limited,decoder),json->{if(!first[0])writer.write(",\n");writer.write(json);first[0]=false;});byte[] tail=new byte[8192];while(limited.read(tail)!=-1){}
            }
            report[0].put("source_sha256",JournalRecovery.hex(original.digest())).put("source_modified",false);if(report[0].optLong("recovered_events")==0)throw new IOException("Aucun événement complet récupérable dans cette source");writer.write("\n],\"recovery_report\":"+report[0].toString()+"}");
        });
    }
}