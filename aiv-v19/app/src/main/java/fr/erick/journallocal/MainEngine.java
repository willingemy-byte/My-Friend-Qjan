package fr.erick.journallocal;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import org.json.*;
import java.util.*;

/** Deterministic writer; Watcher only schedules this fixed entry point. */
public final class MainEngine {
    private final Context context;
    public MainEngine(Context context){this.context=context.getApplicationContext();}
    static String decisionPayload(JSONObject d)throws Exception{return ChainStore.pack("DECISION",d.getString("id"),d.getString("event_id"),d.getString("rule_name"),d.getString("decision"),d.getString("reason"),d.getString("timestamp_ms"),d.getString("enforcement"));}
    private static Boolean permission(JSONObject app,String name)throws Exception{
        if(app==null)return null;JSONArray ps=app.optJSONArray("permissions");if(ps==null)return null;
        for(int i=0;i<ps.length();i++)if(name.equals(ps.getJSONObject(i).optString("name")))return true;return false;
    }
    private static final class EvidenceCache {
        long scan=-1,ended;
        final Map<String,JSONObject> apps=new HashMap<>();
        final Map<String,JSONArray> references=new HashMap<>();
    }
    private JSONObject evidence(SQLiteDatabase db,JSONObject e,EvidenceCache cache)throws Exception{
        JSONObject out=new JSONObject(),d=e.optJSONObject("details");if(d==null)d=new JSONObject();JSONArray pkgs=d.optJSONArray("packages");
        int uid=d.optInt("uid",-1);String pkg=pkgs!=null&&pkgs.length()==1&&uid>=0&&uid%100000!=1000?pkgs.getString(0):"";
        out.put("package",pkg).put("uid",uid).put("appops","OTHER_APPS_NOT_ACCESSIBLE").put("visible_app",JSONObject.NULL);
        SQLiteDatabase audit=PermissionAudit.get(context).getReadableDatabase();
        // A completed inventory is immutable. Capture once per bounded batch, without an audit writer lock.
        if(cache.scan<0){cache.scan=0;try(Cursor c=audit.rawQuery("SELECT id,ended_ms FROM scans WHERE ended_ms IS NOT NULL ORDER BY id DESC LIMIT 1",null)){if(c.moveToFirst()){cache.scan=c.getLong(0);cache.ended=c.getLong(1);}}}
        if(cache.scan>0&&!pkg.isEmpty()&&!cache.apps.containsKey(pkg)){
            JSONObject app=null;
            try(Cursor c=audit.rawQuery("SELECT payload FROM apps WHERE scan_id=? AND package_name=?",new String[]{""+cache.scan,pkg})){if(c.moveToFirst())app=new JSONObject(c.getString(0));}
            if(app!=null){JSONArray ps=new JSONArray();for(String name:new String[]{"android.permission.INTERNET","android.permission.READ_CONTACTS"})if(Boolean.TRUE.equals(permission(app,name)))ps.put(EventStore.object("name",name));app=EventStore.object("package_name",pkg,"uid",app.optInt("uid",-1),"system_app",app.optBoolean("system_app"),"version_code",app.optLong("version_code"),"permissions",ps);}
            cache.apps.put(pkg,app);
        }
        JSONObject app=cache.apps.get(pkg);out.put("scan_id",cache.scan).put("scan_ended_ms",cache.ended).put("inventory_app",app==null?JSONObject.NULL:app);
        long age=e.getLong("timestamp_ms")-cache.ended;
        out.put("inventory_applicable",cache.scan>0&&age>=0&&age<=86400000&&!pkg.isEmpty());
        JSONArray refs=cache.references.get(pkg);
        if(refs==null){refs=new JSONArray();if(!pkg.isEmpty())try(Cursor c=db.rawQuery("SELECT source,fetched_ms,payload FROM reference_apps WHERE package_name=? ORDER BY source",new String[]{pkg})){while(c.moveToNext()){JSONObject row=AivStore.row(c),ref=new JSONObject(row.getString("payload"));String original=row.getString("payload");row.put("payload_sha256",ChainStore.hex(ChainStore.digest().digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))));row.put("payload",EventStore.object("expected_ips",row.getString("source").equals("user")&&ref.has("expected_ips")?ref.getJSONArray("expected_ips"):JSONObject.NULL).toString());refs.put(row);}}cache.references.put(pkg,refs);}
        out.put("references",refs);return out;
    }
    private CoherenceRules.Facts facts(JSONObject e,JSONObject context)throws Exception{
        CoherenceRules.Facts f=new CoherenceRules.Facts();JSONObject d=e.optJSONObject("details");if(d==null)d=new JSONObject();
        f.network=e.optString("category").equals("trafic")||e.optString("category").equals("dns");f.uid=d.optInt("uid",-1);f.action=e.optString("action");f.sni=d.optString("tls_sni","");
        if(d.has("tx_bytes")&&!d.isNull("tx_bytes")){long v=d.getLong("tx_bytes");if(v>=0)f.txBytes=v;}
        JSONObject app=context.optJSONObject("inventory_app");
        if(context.optBoolean("inventory_applicable")){f.inventoried=app!=null;f.internet=permission(app,"android.permission.INTERNET");f.contacts=permission(app,"android.permission.READ_CONTACTS");if(app!=null)f.system=app.optBoolean("system_app");}
        // A public app catalogue has no authoritative app -> destination allowlist.
        // Therefore R1's destination remains UNKNOWN until explicit local reference data says otherwise.
        JSONArray refs=context.getJSONArray("references");String destination=d.optString("remote_ip","");
        for(int i=0;i<refs.length();i++){JSONObject ref=new JSONObject(refs.getJSONObject(i).getString("payload"));JSONArray ips=ref.optJSONArray("expected_ips");if(ips!=null){boolean known=false;for(int j=0;j<ips.length();j++)known|=destination.equals(ips.getString(j));f.knownDestination=known;break;}}
        // R2 and R3 require evidence unavailable to an ordinary APK: leave UNKNOWN.
        return f;
    }
    private List<CoherenceRules.Rule> load(SQLiteDatabase db,Map<Long,JSONObject> snapshots)throws Exception{
        List<CoherenceRules.Rule> rules=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT * FROM main_rules r WHERE version=(SELECT MAX(version) FROM main_rules WHERE name=r.name)",null)){while(c.moveToNext()){
            JSONObject row=AivStore.row(c),condition=new JSONObject(row.getString("condition"));CoherenceRules.Rule r=new CoherenceRules.Rule();r.id=row.getLong("id");r.name=row.getString("name");r.decision=row.getString("decision");r.priority=row.getInt("priority");r.version=row.getInt("version");r.enabled=row.getInt("enabled")==1;r.threshold=condition.optLong("threshold",10485760);
            JSONArray domains=condition.optJSONArray("domains");r.domains=new ArrayList<>();if(domains!=null)for(int i=0;i<domains.length();i++)r.domains.add(domains.getString(i));rules.add(r);snapshots.put(r.id,row);
        }}return CoherenceRules.sorted(rules);
    }
    /** Bounded resumable batch. Failure rolls back both chain entries and the checkpoint. */
    public int drain(int limit,WorkBudget budget)throws Exception{
        budget.checkCancelled();
        if(AivStore.verification.startsWith("BROKEN"))throw new IllegalStateException("Chaîne brisée : écriture AIV suspendue");
        SQLiteDatabase db=EventStore.get(context).getWritableDatabase();
        try(Cursor c=db.rawQuery("SELECT 1 FROM events WHERE id>(SELECT checkpoint FROM aiv_state WHERE id=1) LIMIT 1",null)){if(!c.moveToFirst())return 0;}
        int processed=0;EvidenceCache cache=new EvidenceCache();db.beginTransaction();try{
            Map<Long,JSONObject> snapshots=new HashMap<>();List<CoherenceRules.Rule> rules=load(db,snapshots);
            Map<Long,JSONObject> conditions=new HashMap<>();for(CoherenceRules.Rule rule:rules)conditions.put(rule.id,new JSONObject(snapshots.get(rule.id).getString("condition")));
            long at=AivStore.number(db,"SELECT checkpoint FROM aiv_state WHERE id=1"),legacy=AivStore.number(db,"SELECT legacy_ceiling FROM aiv_state WHERE id=1");
            try(Cursor c=db.rawQuery("SELECT "+AivStore.EVENT_COLUMNS+" FROM events WHERE id>? ORDER BY id LIMIT ?",new String[]{""+at,""+Math.max(1,Math.min(limit,WorkBudget.EVENTS))})){while(c.moveToNext()&&budget.next()){
                long id=c.getLong(0),eventTs=c.getLong(1);JSONObject e=new JSONObject(c.getString(7));e.put("id",id);
                String eventPayload=AivStore.eventPayload(c);String[] eh=AivStore.next(db,eventPayload,eventTs);
                db.execSQL("UPDATE events SET hash_prev=?,hash_self=? WHERE id=? AND hash_self IS NULL",new Object[]{eh[1],eh[2],id});AivStore.append(db,id,null,"EVENT",eventPayload,eventTs,eh);
                JSONObject evidence=evidence(db,e,cache);CoherenceRules.Facts f=facts(e,evidence);JSONArray trace=new JSONArray();CoherenceRules.Rule winner=null;boolean unknown=false;
                for(CoherenceRules.Rule rule:rules){JSONObject snap=snapshots.get(rule.id),condition=conditions.get(rule.id);
                    f.whitelisted=null;if(condition.optBoolean("whitelist_configured")){f.whitelisted=false;JSONArray white=condition.optJSONArray("whitelist");if(white!=null)for(int j=0;j<white.length();j++){String w=white.getString(j);if((e.optJSONObject("details")!=null&&e.getJSONObject("details").optString("remote_ip","").equals(w))||CoherenceRules.domainMatches(f.sni,w))f.whitelisted=true;}}
                    CoherenceRules.Match result=CoherenceRules.evaluate(rule,f);trace.put(EventStore.object("rule",snap,"result",result.name()));if(rule.enabled&&result==CoherenceRules.Match.UNKNOWN)unknown=true;if(winner==null&&result==CoherenceRules.Match.YES)winner=rule;
                }
                long now=System.currentTimeMillis(),decisionId=AivStore.number(db,"SELECT COALESCE(MAX(id),0)+1 FROM decisions");
                JSONObject reason=EventStore.object("evaluation_ms",now,"historical_backfill",id<=legacy,"event_hash",eh[2],"evidence",evidence,"rules",trace,"meaning",winner==null?(unknown?"Observation insuffisante pour conclure":"Aucune règle active correspondante"):"Correspondance de règle; intention non inférée","scope","Observation après événement; aucun blocage rétroactif; volumes cumulatifs par flux non additionnés");
                JSONObject decision=EventStore.object("id",decisionId,"event_id",id,"rule_name",winner==null?"DEFAULT":winner.name,"decision",winner==null?(unknown?"WATCH":"ALLOW"):winner.decision,"reason",reason.toString(),"timestamp_ms",now,"enforcement","NOT_ENFORCED");
                String p=decisionPayload(decision);String[] dh=AivStore.next(db,p,now);ContentValues v=new ContentValues();v.put("id",decisionId);v.put("event_id",id);v.put("rule_name",decision.getString("rule_name"));v.put("decision",decision.getString("decision"));v.put("reason",reason.toString());v.put("timestamp_ms",now);v.put("hash_prev",dh[1]);v.put("hash_self",dh[2]);db.insertOrThrow("decisions",null,v);AivStore.append(db,id,decisionId,"DECISION",p,now,dh);
                at=id;processed++;
            }}budget.checkCancelled();if(processed>0)db.execSQL("UPDATE aiv_state SET checkpoint=? WHERE id=1",new Object[]{at});db.setTransactionSuccessful();
        }finally{db.endTransaction();}return processed;
    }
    /** Local explicit policy editing: append a version; no executable SQL/JS condition. */
    public static void configure(Context ctx,String json)throws Exception{
        if(json.length()>32768)throw new IllegalArgumentException("Configuration trop longue");JSONObject input=new JSONObject(json);String name=input.getString("name"),decision=input.getString("decision");
        if(!name.matches("R[1-6]")||!decision.matches("ALLOW|DENIED|WATCH"))throw new IllegalArgumentException("Règle invalide");JSONObject condition=input.getJSONObject("condition");
        if(!name.equals(condition.getString("type"))||condition.optLong("threshold",10485760)<0)throw new IllegalArgumentException("Condition invalide");
        for(String key:new String[]{"domains","whitelist"}){JSONArray list=condition.optJSONArray(key);if(list!=null){if(list.length()>100)throw new IllegalArgumentException("Liste trop longue");for(int i=0;i<list.length();i++)if(!list.getString(i).matches("[A-Za-z0-9.:-]{1,253}"))throw new IllegalArgumentException("Nom invalide");}}
        SQLiteDatabase db=EventStore.get(ctx).getWritableDatabase();db.beginTransaction();try{
            long version;try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(version),0)+1 FROM main_rules WHERE name=?",new String[]{name})){c.moveToFirst();version=c.getLong(0);}
            db.execSQL("INSERT INTO main_rules(name,condition,decision,priority,enabled,version,created_ms) VALUES(?,?,?,?,?,?,?)",new Object[]{name,condition.toString(),decision,input.getInt("priority"),input.getBoolean("enabled")?1:0,version,System.currentTimeMillis()});
            // Source event participates in this same transaction and is sealed on next drain.
            long now=System.currentTimeMillis();JSONObject e=EventStore.object("timestamp_ms",now,"app","Utilisateur","action","Règle AIV modifiée","destination",name,"transport","Interne","category","aiv-policy","details",input,"policy_version",version);
            ContentValues v=new ContentValues();v.put("timestamp_ms",now);v.put("app","Utilisateur");v.put("action","Règle AIV modifiée");v.put("destination",name);v.put("transport","Interne");v.put("category","aiv-policy");v.put("payload",e.toString());v.put("search_text",e.toString().toLowerCase(Locale.ROOT));db.insertOrThrow("events",null,v);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
}