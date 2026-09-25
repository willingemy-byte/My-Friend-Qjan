package fr.erick.journallocal;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;
/** Optional imported evidence, separate from live observations. */
final class DiagnosticStore extends SQLiteOpenHelper {
    private static DiagnosticStore instance;private final Context context;static volatile String activity="",lastError="";
    static synchronized DiagnosticStore get(Context c){if(instance==null)instance=new DiagnosticStore(c.getApplicationContext());return instance;}
    private DiagnosticStore(Context c){super(c,"diagnostics.sqlite",null,1);context=c;setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE imports(id INTEGER PRIMARY KEY AUTOINCREMENT,payload TEXT NOT NULL)");db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,import_id INTEGER NOT NULL,timestamp_ms INTEGER NOT NULL,payload TEXT NOT NULL)");db.execSQL("CREATE INDEX diagnostic_time ON entries(timestamp_ms)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Migration de diagnostics requise");}
    JSONObject settings(){SharedPreferences p=context.getSharedPreferences("diagnostic",0);return EventStore.object("year",p.getInt("year",java.time.LocalDate.now().getYear()),"utc_offset_minutes",p.getInt("offset",TimeZone.getDefault().getOffset(System.currentTimeMillis())/60000),"window_seconds",p.getInt("window",2));}
    JSONObject configure(String json)throws Exception{JSONObject in=new JSONObject(json);int year=in.getInt("year"),offset=in.getInt("utc_offset_minutes"),window=in.getInt("window_seconds");if(year<1970||year>2100||offset< -840||offset>840||window<1||window>60)throw new IllegalArgumentException("Année 1970–2100, décalage UTC ±840 minutes, fenêtre 1–60 secondes.");context.getSharedPreferences("diagnostic",0).edit().putInt("year",year).putInt("offset",offset).putInt("window",window).apply();return settings();}
    JSONObject summary()throws Exception{
        JSONArray imports=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM imports ORDER BY id DESC LIMIT 20",null)){while(c.moveToNext()){JSONObject j=new JSONObject(c.getString(1));j.put("id",c.getLong(0));imports.put(j);}}
        long count;try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries",null)){c.moveToFirst();count=c.getLong(0);}return EventStore.object("records",count,"imports",imports,"settings",settings(),"activity",activity,"error",lastError,"system_logcat_live",false);
    }
    void ingest(Uri uri)throws Exception{
        activity="Lecture du diagnostic…";lastError="";JSONObject options=settings();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            ContentValues values=new ContentValues();values.put("payload","{}");long importId=db.insertOrThrow("imports",null,values);long[] counts={0,0,0};MessageDigest digest=MessageDigest.getInstance("SHA-256");String member="texte fourni";
            try(InputStream raw=context.getContentResolver().openInputStream(uri)){
                if(raw==null)throw new IOException("Diagnostic indisponible");BufferedInputStream input=new BufferedInputStream(raw);input.mark(4);int first=input.read(),second=input.read();input.reset();
                if(first=='P'&&second=='K'){
                    boolean found=false;try(ZipInputStream zip=new ZipInputStream(input)){
                        ZipEntry entry;int inspected=0;long skippedBytes=0;byte[] skipBuffer=new byte[8192];
                        while((entry=zip.getNextEntry())!=null&&inspected++<100){String name=entry.getName().toLowerCase(Locale.ROOT);
                            if(!entry.isDirectory()&&name.matches("(?:.*/)?bugreport[^/]*\\.txt")){member=DiagnosticParser.safe(entry.getName());readLines(zip,db,importId,counts,digest,options);found=true;break;}
                            if(entry.getSize()>64L*1024*1024)throw new IOException("Entrée ZIP trop grande avant le rapport; importer le texte bugreport seul");int n;while((n=zip.read(skipBuffer))!=-1)if((skippedBytes+=n)>64L*1024*1024)throw new IOException("Trop de données avant le rapport; importer le texte bugreport seul");
                        }
                    }if(!found)throw new IOException("Ce ZIP ne contient pas de fichier bugreport*.txt accessible parmi les 100 premières entrées");
                }else readLines(input,db,importId,counts,digest,options);
            }
            if(counts[1]==0)throw new IOException("Aucune ligne logcat horodatée avec PID, ni preuve de socket au format pris en charge");
            JSONObject info=EventStore.object("imported_utc",java.time.Instant.now().toString(),"member",member,"lines_seen",counts[0],"records",counts[1],"unparsed_lines",counts[2],"sha256_text_bytes",JournalRecovery.hex(digest.digest()),"settings_at_import",options,"scope","Métadonnées sélectionnées seulement. Messages bruts non conservés. Appareil et horloge du fichier à vérifier.");
            values=new ContentValues();values.put("payload",info.toString());db.update("imports",values,"id=?",new String[]{String.valueOf(importId)});db.setTransactionSuccessful();activity=counts[1]+" lignes de diagnostic indexées.";
        }catch(Exception e){lastError="Import du diagnostic : "+e.getMessage();activity="";throw e;}finally{db.endTransaction();}
    }
    private void readLines(InputStream input,SQLiteDatabase db,long importId,long[] counts,MessageDigest digest,JSONObject options)throws Exception{
        InputStream limited=new FilterInputStream(new java.security.DigestInputStream(input,digest)){long n=0;@Override public int read(byte[] b,int off,int len)throws IOException{int r=super.read(b,off,len);if(r>0&&(n+=r)>64L*1024*1024)throw new IOException("Diagnostic de plus de 64 Mio; importer un extrait daté");return r;}};
        Reader r=new BufferedReader(new InputStreamReader(limited,StandardCharsets.UTF_8));StringBuilder line=new StringBuilder();int c;boolean overflow=false;
        while((c=r.read())!=-1){if(c=='\n'){insert(line.toString(),overflow,db,importId,counts,options);line.setLength(0);overflow=false;}else if(line.length()<16384)line.append((char)c);else overflow=true;}if(line.length()>0||overflow)insert(line.toString(),overflow,db,importId,counts,options);
    }
    private void insert(String text,boolean overflow,SQLiteDatabase db,long importId,long[] counts,JSONObject options)throws Exception{
        counts[0]++;if(counts[0]>500000)throw new IOException("Diagnostic de plus de 500 000 lignes");JSONObject parsed=null;try{if(!overflow)parsed=DiagnosticParser.parse(text,options.getInt("year"),options.getInt("utc_offset_minutes"));}catch(Exception ignored){}
        if(parsed==null){counts[2]++;return;}if(counts[1]>=200000)throw new IOException("Diagnostic de plus de 200 000 lignes reconnues");parsed.put("source_line",counts[0]).put("import_id",importId);ContentValues v=new ContentValues();v.put("import_id",importId);v.put("timestamp_ms",parsed.getLong("timestamp_ms"));v.put("payload",parsed.toString());db.insertOrThrow("entries",null,v);counts[1]++;
    }
    JSONObject compare(JSONObject event)throws Exception{
        JSONObject d=event.optJSONObject("details");long at=d==null?event.optLong("timestamp_ms"):d.optLong("first_observed_ms",event.optLong("timestamp_ms"));if(at<=0)throw new IllegalArgumentException("Heure de début du flux inconnue");long window=settings().getLong("window_seconds")*1000;List<JSONObject> rows=new ArrayList<>();long total;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE timestamp_ms BETWEEN ? AND ?",new String[]{String.valueOf(at-window),String.valueOf(at+window)})){c.moveToFirst();total=c.getLong(0);}
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload FROM entries WHERE timestamp_ms BETWEEN ? AND ? ORDER BY ABS(timestamp_ms-?),id LIMIT 200",new String[]{String.valueOf(at-window),String.valueOf(at+window),String.valueOf(at)})){while(c.moveToNext()){JSONObject row=new JSONObject(c.getString(1));row.put("diagnostic_id",c.getLong(0)).put("delta_ms",row.getLong("timestamp_ms")-at).put("match",DiagnosticParser.match(event,row));rows.add(row);}}
        Collections.sort(rows,(a,b)->Integer.compare(rank(a.optString("match")),rank(b.optString("match"))));JSONArray candidates=new JSONArray();for(int i=0;i<Math.min(20,rows.size());i++)candidates.put(rows.get(i));String status=rows.isEmpty()?"aucune_correspondance_dans_la_fenetre":rows.get(0).getString("match");
        return EventStore.object("event_id",event.optLong("id"),"flow_id",d==null?JSONObject.NULL:d.optString("flow_id"),"flow_start_ms",at,"window_ms_each_side",window,"lines_in_window",total,"lines_examined",rows.size(),"candidates",candidates,"status",status,"package_attribution_confirmed",false,"service_attribution_confirmed",false,
            "interpretation",status.equals("socket_tuple_reported")?"Un diagnostic rapporte le même socket, UID et PID dans la fenêtre. Vérifier provenance, appareil, horloges et réutilisation du socket/PID. Cela ne distingue pas les services d’un même processus.":status.equals("endpoint_and_time")?"Une ligne logcat mentionne la même destination à proximité. Le PID est celui qui a écrit la ligne, pas un propriétaire de socket prouvé.":"Proximité d’heure seulement ou absence de ligne. Aucune attribution du flux à un paquet n’est établie.");
    }
    private static int rank(String match){return match.equals("socket_tuple_reported")?0:match.equals("endpoint_and_time")?1:2;}
}