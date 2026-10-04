package fr.erick.journallocal;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Portable SQLite export of the observations, without private settings or WAL copying. */
final class SQLiteSnapshot {
    static void write(File file,SnapshotExporter.Source source)throws Exception{
        long[] snapshot=source.snapshot();long ceiling=snapshot[0],expected=snapshot[1],after=0,count=0;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");ExportMetadata.Records records=new ExportMetadata.Records();
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null)){
            db.execSQL("PRAGMA journal_mode=DELETE");db.execSQL("PRAGMA synchronous=FULL");
            db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY,timestamp_ms INTEGER NOT NULL,payload TEXT NOT NULL)");
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            db.beginTransaction();
            try{
                while(after<ceiling){
                    java.util.List<String> page=source.page(after,ceiling);if(page.isEmpty())break;
                    for(String raw:page){
                        JSONObject event=new JSONObject(raw);long id=event.getLong("id");
                        if(id<=after||id>ceiling)throw new IOException("Ordre de l’instantané SQLite invalide");
                        ContentValues row=new ContentValues();row.put("id",id);row.put("timestamp_ms",event.getLong("timestamp_ms"));row.put("payload",raw);
                        db.insertOrThrow("events",null,row);digest.update((raw+"\n").getBytes(StandardCharsets.UTF_8));records.add(event);after=id;count++;
                    }
                }
                if(count!=expected||after!=ceiling)throw new IOException("Instantané SQLite incomplet");
                JSONObject header=ExportMetadata.header("aiv-journal-snapshot-sqlite/1",ceiling,expected);
                header.put("integrity",EventStore.object("complete",true,"event_count",count,"last_id",after,"sha256_events",JournalRecovery.hex(digest.digest()),"hash_encoding","UTF-8 des payload dans l’ordre id, chacun suivi de LF"));
                header.put("quality",records.json());ContentValues metadata=new ContentValues();metadata.put("key","snapshot");metadata.put("payload",header.toString());
                db.insertOrThrow("metadata",null,metadata);db.execSQL("CREATE INDEX events_time ON events(timestamp_ms,id)");db.execSQL("PRAGMA user_version=1");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            try(Cursor check=db.rawQuery("PRAGMA quick_check",null)){if(!check.moveToFirst()||!"ok".equals(check.getString(0)))throw new IOException("Vérification SQLite échouée");}
        }
    }
}
