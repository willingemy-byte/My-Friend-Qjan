package fr.erick.journallocal;

import android.content.Context;
import java.io.*;
import org.json.*;

/** Read-time context only; capture and the append-only journal never depend on this catalogue. */
final class NetworkReport {
    private static EndpointContextRules rules;
    private static synchronized EndpointContextRules get(Context c)throws Exception{
        if(rules==null)try(InputStream in=c.getAssets().open("catalogs/endpoints.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("Catalogue trop grand");out.write(b,0,n);}
            rules=new EndpointContextRules(new JSONObject(out.toString("UTF-8")));
        }return rules;
    }
    static void enrich(Context c,JSONObject e){
        if(e==null||!EndpointContextRules.isNetwork(e))return;
        try{
            JSONObject d=EndpointContextRules.details(e);ReferenceCatalog catalog=ReferenceCatalog.get(c);
            boolean dns="dns".equals(e.optString("category"))&&!d.optString("question").isEmpty();
            String evidence=dns?"DNS_QUERY_ONLY":d.optBoolean("ech_extension_present")?"TLS_OUTER_NAME":"TLS_SNI";
            JSONArray matches=catalog.network(dns?d.optString("question"):d.optString("tls_sni"),evidence);
            JSONArray queries=d.optJSONArray("tracker_dns_candidates");
            e.put("network_context",get(c).analyze(e,matches,queries).put("exodus_catalogue_revision",catalog.revision()));
            try{AnomalyMonitor.get(c).pedigree(e,false);}catch(Exception ignored){}
        }catch(Exception failure){try{e.put("network_context",EventStore.object("status","UNAVAILABLE","summary","Service à identifier · catalogue indisponible","error",failure.getClass().getSimpleName()));}catch(Exception ignored){}}
    }
    static String brief(JSONObject e){String brief=EndpointContextRules.brief(e);JSONObject p=e.optJSONObject("endpoint_pedigree");if(p==null)return brief;String status=p.optString("status");String history="KNOWN_FOR_APP".equals(status)?"Déjà observé pour cette version":"NEW_FOR_APP".equals(status)?"Première observation pour cette version":"";return history.isEmpty()?brief:brief+"\n"+history;}
    static String trackersBrief(JSONObject e){return EndpointContextRules.trackersBrief(e);}
    static String explain(JSONObject e){String text=EndpointContextRules.explain(e);JSONObject p=e.optJSONObject("endpoint_pedigree");return p==null?text:text+"\n\nPedigree application / signature / version\n"+p+"\nConnu ne signifie pas sûr.";}
    static JSONObject anomalyContext(JSONArray events)throws Exception{return EndpointContextRules.aggregate(events);}
    static JSONObject catalogueSummary(Context c)throws Exception{return get(c).metadata();}
}
