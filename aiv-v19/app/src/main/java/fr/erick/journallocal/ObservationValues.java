package fr.erick.journallocal;

import org.json.*;

/** Shared meanings for raw records, derived flows, native UI and export. */
final class ObservationValues {
    static final String COUNTER_UNIT="IP_PACKET_BYTES_ON_LOCAL_VPN_INTERFACE";
    static final String COUNTER_SCOPE="Paquets IP de l’interface VPN locale, en-têtes et retransmissions inclus; les réponses TCP produites par le relais local sont comptées. Aucune mesure de facturation opérateur ni taille de message applicatif n’est déduite.";
    static final String[] COUNTERS={"tx_bytes","rx_bytes","tx_packets","rx_packets"};
    static boolean valid(JSONObject d,String key){
        if(d==null||!d.has(key)||d.isNull(key))return false;
        Object n=d.opt(key);
        return (n instanceof Long||n instanceof Integer)&&((Number)n).longValue()>=0;
    }
    static boolean countersKnown(JSONObject d){
        for(String key:COUNTERS)if(!valid(d,key))return false;
        return d.optLong("tx_bytes")<=Long.MAX_VALUE-d.optLong("rx_bytes");
    }
    static Object counter(JSONObject d,String key){return valid(d,key)?d.optLong(key):JSONObject.NULL;}
    static void emptyCounters(JSONObject d)throws JSONException{
        for(String key:COUNTERS)d.put(key,JSONObject.NULL);
        d.put("bytes",JSONObject.NULL).put("volume_status","NOT_YET_OBSERVED");
    }
    static boolean putCounters(JSONObject d,long tx,long rx,long tp,long rp)throws JSONException{
        boolean ok=tx>=0&&rx>=0&&tp>=0&&rp>=0&&tx<=Long.MAX_VALUE-rx;
        if(ok){
            d.put("tx_bytes",tx).put("rx_bytes",rx).put("tx_packets",tp).put("rx_packets",rp).put("bytes",tx+rx).put("volume_status","OBSERVED");
        }else{
            emptyCounters(d);d.put("volume_status","INVALID").put("invalid_counters",EventStore.object("tx_bytes",tx,"rx_bytes",rx,"tx_packets",tp,"rx_packets",rp));
        }
        d.put("counter_mode","Cumul du flux; ne pas additionner les instantanés")
            .put("counter_unit",COUNTER_UNIT).put("counter_scope",COUNTER_SCOPE);
        return ok;
    }
    static void mergeCounters(JSONObject target,JSONObject source)throws JSONException{
        if("INVALID".equals(source.optString("volume_status")))target.put("counter_warning","INVALID_SNAPSHOT");
        else if("COUNTER_SNAPSHOT".equals(source.optString("observation_type"))&&countersKnown(source))target.remove("counter_warning");
        for(String key:COUNTERS){
            if(valid(source,key)&&(!valid(target,key)||source.optLong(key)>target.optLong(key)))target.put(key,source.optLong(key));
            else if(!target.has(key))target.put(key,JSONObject.NULL);
        }
        boolean known=countersKnown(target);
        target.put("volume_status",known?"OBSERVED":"UNKNOWN")
            .put("bytes",known?target.optLong("tx_bytes")+target.optLong("rx_bytes"):JSONObject.NULL);
    }
    static boolean uniquePackage(JSONObject d){
        if(d==null||d.optBoolean("identity_conflict"))return false;
        int uid=d.optInt("uid",-1);JSONArray p=d.optJSONArray("packages");
        return uid>=0&&uid%100000>=10000&&p!=null&&p.length()==1&&!p.optString(0).isEmpty();
    }
    static String attribution(JSONObject d){
        if(d!=null&&d.optBoolean("identity_conflict"))return "CONFLICTING_UID";
        if(d==null||d.optInt("uid",-1)<0)return "UNKNOWN";
        JSONArray p=d.optJSONArray("packages");
        if(p!=null&&p.length()>1)return "SHARED_UID";
        if(d.optInt("uid")%100000<10000)return "RESERVED_UID";
        return uniquePackage(d)?"ATTRIBUTED_PACKAGE":"UID_WITHOUT_PACKAGE";
    }
    static void mergeIdentity(JSONObject target,JSONObject source,String actor)throws JSONException{
        if(target.optBoolean("identity_conflict"))return;
        int old=target.optInt("uid",-1),uid=source.optInt("uid",-1);
        if(old>=0&&uid<0){target.put("identity_missing_at_latest_event",true);return;}
        if(old>=0&&uid>=0&&old!=uid){
            target.put("identity_conflict",true).put("uid_candidates",new JSONArray().put(old).put(uid))
                .put("uid",-1).put("packages",new JSONArray()).put("app","Application non identifiée")
                .put("journal_group","unknown").put("attribution","UID contradictoires pour la même corrélation; auteur inconnu");return;
        }
        for(String key:new String[]{"attribution","journal_group","identity_status","identity_attempts","identity_error"})if(source.has(key)&&!source.isNull(key))target.put(key,source.get(key));
        target.put("uid",uid);JSONArray p=source.optJSONArray("packages"),previous=target.optJSONArray("packages");
        if(p!=null&&(p.length()>0||previous==null||previous.length()==0))target.put("packages",p);
        if(!target.has("packages"))target.put("packages",new JSONArray());
        String state=attribution(target);
        if("UNKNOWN".equals(state))target.put("app","Application non identifiée").put("journal_group","unknown");
        else if("UID_WITHOUT_PACKAGE".equals(state))target.put("app","Application inconnue · UID "+uid).put("journal_group","unknown");
        else if("RESERVED_UID".equals(state))target.put("app","Android · UID "+uid).put("journal_group","android");
        else if("SHARED_UID".equals(state))target.put("app","UID partagé · "+uid).put("journal_group","shared");
        else if(uniquePackage(source)||!target.has("app"))target.put("app",actor);
    }
    static JSONObject confidence(JSONObject d){
        return EventStore.object("connection","OBSERVÉ","uid",d.optInt("uid",-1)>=0?"OBSERVÉ":"INCONNU",
            "package",uniquePackage(d)?"ATTRIBUÉ":"INCONNU","volume",countersKnown(d)?"OBSERVÉ":"INCONNU",
            "dns_question",d.optString("question").isEmpty()?"INCONNU":"OBSERVÉ",
            "tls_name",d.optString("tls_sni").isEmpty()?"INCONNU":"OBSERVÉ",
            "originating_application", "INCONNU", "scope","Une question DNS ou un nom SNI ne prouve ni le contenu échangé ni une destination ultérieure.");
    }
}
