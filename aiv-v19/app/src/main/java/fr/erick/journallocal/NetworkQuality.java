package fr.erick.journallocal;

import org.json.*;

/** Rates for a stated set of flows. A zero denominator is unknown, never 100%. */
final class NetworkQuality {
    static JSONObject flows(JSONArray flows){
        long eligible=0,attributed=0,unknown=0,shared=0,system=0,volume=0,tx=0,rx=0,issues=0;
        boolean overflow=false;
        for(int i=0;i<flows.length();i++){
            JSONObject f=flows.optJSONObject(i);if(f==null)continue;
            if(!f.optString("counter_warning").isEmpty())issues++;
            String attribution=ObservationValues.attribution(f);
            if("SHARED_UID".equals(attribution))shared++;
            else if("RESERVED_UID".equals(attribution))system++;
            else if(("TCP".equals(f.optString("protocol"))||"UDP".equals(f.optString("protocol")))&&!"UNSUPPORTED".equals(f.optString("identity_status"))){
                eligible++;if(ObservationValues.uniquePackage(f))attributed++;else unknown++;
            }
            if(ObservationValues.countersKnown(f)){
                volume++;
                if(tx>Long.MAX_VALUE-f.optLong("tx_bytes")||rx>Long.MAX_VALUE-f.optLong("rx_bytes"))overflow=true;
                else{tx+=f.optLong("tx_bytes");rx+=f.optLong("rx_bytes");}
            }
        }
        return EventStore.object("connections",flows.length(),"attributable_connections",eligible,"attributed_connections",attributed,
            "unknown_connections",unknown,"shared_uid_connections",shared,"reserved_uid_connections",system,
            "attribution_rate",rate(attributed,eligible),"unknown_rate",rate(unknown,eligible),"volume_coverage",rate(volume,flows.length()),
            "connections_with_valid_counters",volume,"tx_bytes_observed",overflow||volume==0?JSONObject.NULL:tx,"rx_bytes_observed",overflow||volume==0?JSONObject.NULL:rx,
            "volume_complete",volume==flows.length()&&issues==0,"counter_issue_connections",issues,"counter_sum_overflow",overflow,
            "scope","Flux de cette page uniquement, après enrichissement. Un cumul maximum par connexion; les instantanés ne sont pas additionnés. Les UID partagés/réservés et protocoles non pris en charge sont exclus du dénominateur d’attribution unique.");
    }
    static Object rate(long n,long total){return total==0?JSONObject.NULL:(double)n/total;}
}
