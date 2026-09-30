package fr.erick.journallocal;
import java.io.Writer;
import java.time.Instant;
import org.json.JSONObject;
/** Fixed snapshot contract, testable while the source is appended. */
final class SnapshotExporter {
    interface Source {long[] snapshot()throws Exception;java.util.List<String> page(long after,long ceiling)throws Exception;}
    static void write(Writer writer,boolean jsonl,Source source)throws Exception{
        long[] snapshot=source.snapshot();long maxId=snapshot[0],expected=snapshot[1];
        JSONObject header=EventStore.object("schema","journal-cellulaire/2","source","Journal local Android","snapshot_max_id",maxId,"expected_event_count",expected,"snapshot_utc",Instant.now().toString(),"application_version","0.6.20","coverage","États Android, métadonnées VPN optionnelles, DNS UDP clair et premier ClientHello TLS/TCP dans une limite de 32 Kio; pas de déchiffrement, pas de PID système automatique");
        if(jsonl){header.put("type","header");writer.write(header.toString()+"\n");}else{String h=header.toString();writer.write(h.substring(0,h.length()-1)+",\"events\":[\n");}
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");long after=0,count=0;
        while(after<maxId){java.util.List<String> page=source.page(after,maxId);if(page.isEmpty())break;for(String raw:page){long id=new JSONObject(raw).getLong("id");if(id<=after||id>maxId)throw new java.io.IOException("Ordre ou limite de l’instantané invalide");after=id;if(!jsonl&&count>0)writer.write(",\n");writer.write(raw);if(jsonl)writer.write("\n");digest.update((raw+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));count++;}}
        if(count!=expected||after!=maxId)throw new java.io.IOException("Instantané incomplet : "+count+" / "+expected);
        JSONObject integrity=EventStore.object("complete",true,"event_count",count,"last_id",after,"sha256_events",JournalRecovery.hex(digest.digest()),"hash_encoding","UTF-8 de chaque objet événement exporté suivi de LF; séparateurs du tableau exclus","finished_utc",Instant.now().toString());
        if(jsonl)writer.write(EventStore.object("type","footer","integrity",integrity).toString()+"\n");else writer.write("\n],\"integrity\":"+integrity.toString()+"}");writer.flush();
    }
}