package fr.erick.journallocal;
import java.io.Writer;
import java.time.Instant;
import org.json.JSONObject;
/** Fixed snapshot contract, testable while the source is appended. */
final class SnapshotExporter {
    interface Source {long[] snapshot()throws Exception;java.util.List<String> page(long after,long ceiling)throws Exception;}
    static void write(Writer writer,boolean jsonl,Source source)throws Exception{
        long[] snapshot=source.snapshot();long maxId=snapshot[0],expected=snapshot[1];
        JSONObject header=ExportMetadata.header("journal-cellulaire/2",maxId,expected);
        if(jsonl){header.put("type","header");writer.write(header.toString()+"\n");}else{String h=header.toString();writer.write(h.substring(0,h.length()-1)+",\"events\":[\n");}
        ExportMetadata.Records records=new ExportMetadata.Records();
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long after=0,count=0;
        while(after<maxId){java.util.List<String> page=source.page(after,maxId);if(page.isEmpty())break;for(String raw:page){JSONObject event=new JSONObject(raw);long id=event.getLong("id");if(id<=after||id>maxId)throw new java.io.IOException("Ordre ou limite de l’instantané invalide");after=id;records.add(event);if(!jsonl&&count>0)writer.write(",\n");writer.write(raw);if(jsonl)writer.write("\n");digest.update((raw+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));count++;}}
        if(count!=expected||after!=maxId)throw new java.io.IOException("Instantané incomplet : "+count+" / "+expected);
        JSONObject integrity=EventStore.object("complete",true,"event_count",count,"last_id",after,"sha256_events",JournalRecovery.hex(digest.digest()),"hash_encoding","UTF-8 de chaque objet événement exporté suivi de LF; séparateurs du tableau exclus","finished_utc",Instant.now().toString());
        if(jsonl)writer.write(EventStore.object("type","footer","integrity",integrity,"quality",records.json()).toString()+"\n");else writer.write("\n],\"integrity\":"+integrity.toString()+",\"quality\":"+records.json().toString()+"}");writer.flush();
    }
}
