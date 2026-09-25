package fr.erick.journallocal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;
/** Salvage only independent, syntactically valid records; preserve original source. */
final class JournalRecovery {
    interface Sink {void event(String json)throws Exception;}
    static final int MAX_RECORD=1024*1024;
    static final class Result {
        long accepted,rejected;boolean complete=false;String format="JSON",issue="",integrity="absente";final MessageDigest digest;JSONObject expected;
        Result()throws Exception{digest=MessageDigest.getInstance("SHA-256");}
        JSONObject finish(){String actual=hex(digest.digest());if(expected!=null)integrity=expected.optLong("event_count",-1)==accepted&&actual.equals(expected.optString("sha256_events"))&&expected.optBoolean("complete")?"verifiee":"echec";
            return EventStore.object("format",format,"recovered_events",accepted,"rejected_records",rejected,"document_complete",complete,"source_integrity",integrity,"sha256_events",actual,"issue",issue,"scope","Seulement les événements complets et valides; événements absents non reconstitués. Aucune insertion dans le journal en cours.");}
    }
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}
    private final PushbackReader input;private JournalRecovery(Reader r){input=new PushbackReader(r,2);}
    private int next()throws IOException{int c;do{c=input.read();}while(c==' '||c=='\t'||c=='\n'||c=='\r'||c==0xfeff);return c;}
    private String token(int first)throws IOException{
        if(first<0)throw new EOFException("Fin de fichier au milieu d’une valeur");StringBuilder b=new StringBuilder();b.append((char)first);boolean quoted=first=='"',escape=false;int depth=first=='{'||first=='['?1:0;
        if(!quoted&&depth==0){int c;while((c=input.read())>=0){if(c==','||c==']'||c=='}'||Character.isWhitespace((char)c)){input.unread(c);break;}b.append((char)c);if(b.length()>MAX_RECORD)throw new IOException("Valeur trop longue");}return b.toString();}
        while(true){int c=input.read();if(c<0)throw new EOFException("Valeur interrompue");b.append((char)c);if(b.length()>MAX_RECORD)throw new IOException("Événement de plus de 1 Mio");
            if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"'){quoted=false;if(depth==0)return b.toString();}}
            else if(c=='"')quoted=true;else if(c=='{'||c=='[')depth++;else if(c=='}'||c==']'){if(--depth==0)return b.toString();}
            if(depth>64)throw new IOException("Imbrication trop profonde");}
    }
    private static boolean accept(String raw,Result r,Sink sink)throws Exception{
        if(!JsonSyntax.valid(raw))return false;JSONObject event;try{event=new JSONObject(raw);}catch(JSONException e){return false;}
        if(!(event.has("timestamp")||event.has("timestamp_ms"))||!(event.has("action")||event.has("app")))return false;
        sink.event(raw);r.digest.update((raw+"\n").getBytes(StandardCharsets.UTF_8));r.accepted++;return true;
    }
    private void array(Result r,Sink sink)throws Exception{
        int c=next();if(c==']')return;while(true){String raw=token(c);if(!accept(raw,r,sink)){r.rejected++;throw new IOException("Événement invalide; récupération arrêtée à cet endroit");}c=next();if(c==']')return;if(c!=',')throw new IOException("Séparateur ou fin de tableau manquant");c=next();}
    }
    private void wrapped(Result r,Sink sink)throws Exception{
        int c=next();if(c=='['){array(r,sink);if(next()!=-1)throw new IOException("Données après le tableau");r.complete=true;return;}if(c!='{')throw new IOException("Un export JSON ou JSONL est attendu");
        boolean found=false;java.util.HashSet<String> keys=new java.util.HashSet<>();c=next();
        while(c!='}'){
            if(c!='"')throw new IOException("Clé JSON manquante");String keyToken=token(c);if(!JsonSyntax.valid(keyToken))throw new IOException("Clé invalide");String key=new JSONArray("["+keyToken+"]").getString(0);
            if(!keys.add(key))throw new IOException("Clé dupliquée");if(next()!=':')throw new IOException("Séparateur de clé manquant");c=next();
            if("events".equals(key)){if(c!='[')throw new IOException("Tableau events attendu");found=true;array(r,sink);}
            else{String raw=token(c);if(!JsonSyntax.valid(raw))throw new IOException("Métadonnée invalide");if("integrity".equals(key))r.expected=new JSONObject(raw);}
            c=next();if(c=='}')break;if(c!=',')throw new IOException("Fin du document manquante");c=next();if(c=='}')throw new IOException("Virgule finale invalide");
        }
        if(!found)throw new IOException("Aucun tableau events");if(next()!=-1)throw new IOException("Données après le document");r.complete=true;
    }
    private static String line(Reader r)throws IOException{StringBuilder b=new StringBuilder();int c;while((c=r.read())>=0&&c!='\n'){b.append((char)c);if(b.length()>MAX_RECORD)throw new IOException("Ligne de plus de 1 Mio");}return c<0&&b.length()==0?null:b.toString();}
    static JSONObject recover(Reader source,Sink sink)throws Exception{
        BufferedReader b=new BufferedReader(source);b.mark(65538);StringBuilder probe=new StringBuilder();int c;while(probe.length()<65536&&(c=b.read())>=0&&c!='\n')probe.append((char)c);b.reset();
        boolean lines=false;try{if(JsonSyntax.valid(probe.toString())){JSONObject p=new JSONObject(probe.toString());lines=!p.has("events")&&(p.has("timestamp")||p.has("timestamp_ms")||"header".equals(p.optString("type")));}}catch(JSONException ignored){}
        Result r=new Result();
        if(lines){r.format="JSONL";String raw;boolean footer=false,header=false;
            while((raw=line(b))!=null){raw=raw.trim();if(raw.isEmpty())continue;JSONObject entry=null;try{if(JsonSyntax.valid(raw))entry=new JSONObject(raw);}catch(JSONException ignored){}
                if(entry!=null&&"header".equals(entry.optString("type"))&&r.accepted==0&&!header){header=true;continue;}
                if(entry!=null&&"footer".equals(entry.optString("type"))&&!footer){r.expected=entry.optJSONObject("integrity");footer=true;continue;}
                if(footer){r.rejected++;r.issue="Données après la fin déclarée";continue;}if(!accept(raw,r,sink)){r.rejected++;r.issue="Lignes invalides ignorées";}
            }r.complete=r.rejected==0&&(!header||footer);if(header&&!footer)r.issue="Fin d’export absente; lignes complètes récupérées";
        }else try{new JournalRecovery(b).wrapped(r,sink);}catch(IOException|JSONException e){r.issue=e.getMessage();r.complete=false;}
        return r.finish();
    }
}