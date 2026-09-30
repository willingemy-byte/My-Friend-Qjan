package fr.erick.journallocal;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** Versioned bundled public references. Never sends installed package names or APKs to a server. */
public final class ReferenceCatalog {
    private static ReferenceCatalog instance;
    public final JSONObject bayton,exodus; public final TrackerMatcher matcher;
    private final Map<Integer,JSONObject> trackers=new HashMap<>();
    private final Map<String,String> networkCache=new LinkedHashMap<String,String>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,String> e){return size()>512;}};
    public static synchronized ReferenceCatalog get(Context c)throws Exception{if(instance==null)instance=new ReferenceCatalog(c);return instance;}
    private ReferenceCatalog(Context c)throws Exception{
        bayton=read(c,"bayton");exodus=read(c,"exodus");List<TrackerMatcher.Signature> signatures=new ArrayList<>();JSONArray rows=exodus.getJSONArray("trackers");
        for(int i=0;i<rows.length();i++){JSONObject t=rows.getJSONObject(i);trackers.put(t.getInt("id"),t);signatures.add(new TrackerMatcher.Signature(t.getInt("id"),t.getString("name"),t.optString("network_signature",""),t.optString("code_signature","")));}
        matcher=new TrackerMatcher(signatures);
    }
    private JSONObject read(Context c,String name)throws Exception{
        try(InputStream in=c.getAssets().open("catalogs/"+name+".json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("Catalog too large");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));
        }
    }
    public String revision(){return exodus.optString("upstream_sha256");}
    public JSONObject permission(String name){return bayton.optJSONObject("permissions").optJSONObject(name);}
    public JSONObject tracker(int id)throws Exception{JSONObject t=trackers.get(id);return t==null?EventStore.object("id",id):new JSONObject(t.toString());}
    public synchronized JSONArray network(String host,String kind)throws Exception{
        String key=kind+":"+host;if(networkCache.containsKey(key))return new JSONArray(networkCache.get(key));JSONArray out=new JSONArray();
        for(TrackerMatcher.Hit hit:matcher.network(host))out.put(EventStore.object("tracker_id",hit.signature.id,"name",hit.signature.name,"evidence",kind,"host",host,"domain_boundary_match",hit.boundary,"status",hit.boundary?"SIGNATURE_MATCH":"PARTIAL_SIGNATURE_CANDIDATE"));
        networkCache.put(key,out.toString());return out;
    }
    public JSONObject summary(){return EventStore.object("bayton_permissions",bayton.optJSONObject("permissions").length(),"bayton_reviewed",bayton.optString("reviewed"),"bayton_api_level",bayton.optInt("api_level"),"device_api_level",android.os.Build.VERSION.SDK_INT,"exodus_trackers",trackers.size(),"fetched_utc",exodus.optString("fetched_utc"),"exodus_revision",revision(),"mode","BUNDLED_OFFLINE","notice","Descriptions Bayton/AOSP; état des permissions fourni par ce téléphone. Aucun envoi de tes APK ou de ton inventaire. Une absence de correspondance ne prouve pas l’absence de traqueur.");}
    public JSONObject flow(JSONObject flow)throws Exception{
        flow.put("tracker_matches",network(flow.optString("tls_sni"),flow.optBoolean("ech_extension_present")?"TLS_OUTER_NAME":"TLS_SNI"));
        JSONArray dns=flow.optJSONArray("dns"),queries=new JSONArray();if(dns!=null)for(int i=0;i<dns.length();i++){JSONArray hits=network(dns.optString(i),"DNS_QUERY_ONLY");for(int j=0;j<hits.length();j++)queries.put(hits.get(j));}
        return flow.put("tracker_dns_candidates",queries).put("tracker_catalog_revision",revision()).put("tracker_scope","Correspondance de signature; aucune preuve du contenu, du SDK appelant ou du traitement après le serveur. DNS seul ne relie pas une connexion ultérieure.");
    }
}
