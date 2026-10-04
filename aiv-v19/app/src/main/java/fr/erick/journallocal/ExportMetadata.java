package fr.erick.journallocal;

import java.time.Instant;
import org.json.*;

final class ExportMetadata {
    static JSONObject header(String schema,long maxId,long expected){
        return EventStore.object("schema",schema,"source","All In Visible Android","snapshot_max_id",maxId,
            "expected_event_count",expected,"snapshot_utc",Instant.now().toString(),
            "application_version",BuildMetadata.VERSION_NAME,"application_version_code",BuildMetadata.VERSION_CODE,
            "build",EventStore.object("source_commit",BuildMetadata.SOURCE_COMMIT,"source_dirty",BuildMetadata.SOURCE_DIRTY,
                "source_sha256",BuildMetadata.SOURCE_SHA256,"built_utc",BuildMetadata.BUILD_UTC),
            "observation_schema","aiv-network-observation/1","counter_unit",ObservationValues.COUNTER_UNIT,"counter_scope",ObservationValues.COUNTER_SCOPE,
            "missing_values","null = non observé/inconnu; 0 = zéro réellement mesuré. UID -1 = propriétaire inconnu.",
            "enrichment","Les observations restent immuables; les événements IDENTITY_ENRICHMENT complètent le même flow_correlation_id.",
            "coverage","Métadonnées VPN optionnelles, DNS UDP clair (questions), premier ClientHello TLS/TCP jusqu’à 32 Kio. Aucun déchiffrement TLS/QUIC, lien DNS→autre IP ou chemin serveur démontré.");
    }
    /** Constant memory; reports records, without summing cumulative traffic snapshots. */
    static final class Records {
        long first=Long.MAX_VALUE,last=0,network=0,enrichments=0,eligible=0,attributed=0,unknown=0;
        long volumeEvents=0,validVolume=0,dns=0,connections=0,identityErrors=0,system=0;
        void add(JSONObject event){
            long ms=event.optLong("timestamp_ms",-1);if(ms>=0){first=Math.min(first,ms);last=Math.max(last,ms);}
            String category=event.optString("category");if(!"trafic".equals(category)&&!"dns".equals(category))return;
            JSONObject d=event.optJSONObject("details");if(d==null)return;
            if("IDENTITY_ENRICHMENT".equals(d.optString("observation_type"))){enrichments++;return;}
            network++;
            if("dns".equals(category))dns++;
            if("Flux réseau observé".equals(event.optString("action")))connections++;
            if(!d.optString("identity_error").isEmpty())identityErrors++;
            String state=ObservationValues.attribution(d),protocol=d.optString("protocol");
            if("RESERVED_UID".equals(state)||"SHARED_UID".equals(state))system++;
            else if(("TCP".equals(protocol)||"UDP".equals(protocol))&&!"UNSUPPORTED".equals(d.optString("identity_status"))){
                eligible++;if(ObservationValues.uniquePackage(d))attributed++;else unknown++;
            }
            boolean packet=d.has("first_packet_bytes"),snapshot="COUNTER_SNAPSHOT".equals(d.optString("observation_type"))||(d.optString("observation_type").isEmpty()&&ObservationValues.countersKnown(d));
            if(packet||snapshot){volumeEvents++;if(ObservationValues.countersKnown(d)||ObservationValues.valid(d,"first_packet_bytes"))validVolume++;}
        }
        JSONObject json(){
            return EventStore.object("first_event_ms",first==Long.MAX_VALUE?JSONObject.NULL:first,"last_event_ms",first==Long.MAX_VALUE?JSONObject.NULL:last,
                "network_observation_records",network,"identity_enrichment_records",enrichments,"connections_opened",connections,"dns_questions",dns,
                "system_or_shared_uid_records",system,"identification_error_records",identityErrors,"attributable_events",eligible,
                "attributed_events",attributed,"unknown_events",unknown,"attribution_rate",NetworkQuality.rate(attributed,eligible),"unknown_rate",NetworkQuality.rate(unknown,eligible),
                "volume_events",volumeEvents,"events_with_valid_volume",validVolume,"volume_coverage",NetworkQuality.rate(validVolume,volumeEvents),
                "scope","Attribution au moment de chaque observation, avant les enrichissements ultérieurs; les flux de la vue native utilisent l’identité enrichie. Volume : premiers paquets et instantanés de compteurs uniquement; DNS/SNI/ouverture ne sont pas des paquets supplémentaires. Aucun cumul de volumes n’est obtenu en additionnant les événements.");
        }
    }
}
