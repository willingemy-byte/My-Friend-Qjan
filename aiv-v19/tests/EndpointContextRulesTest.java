package fr.erick.journallocal;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Synthetic exchanges; public catalogues, no phone data. */
public final class EndpointContextRulesTest {
    static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static JSONObject read(Path p)throws Exception{return new JSONObject(new String(Files.readAllBytes(p),StandardCharsets.UTF_8));}
    static JSONObject event(String host){return EndpointContextRules.object("id",7,"category","trafic","details",EndpointContextRules.object("flow_correlation_id","synthetic-flow","tls_sni",host,"remote_ip","192.0.2.1","port",443,"uid",1000,"packages",new JSONArray().put("example.system").put("example.other"),"tx_bytes",20,"rx_bytes",40,"outbound_observed",true,"inbound_observed",true));}
    static JSONObject analyze(EndpointContextRules rules,JSONObject e)throws Exception{return rules.analyze(e,new JSONArray(),new JSONArray());}
    static JSONObject firstService(JSONObject r)throws Exception{return r.getJSONArray("services").getJSONObject(0);}
    public static void main(String[] args)throws Exception{
        Path assets=Paths.get(args[0]);JSONObject catalog=read(assets.resolve("endpoints.json")),exodus=read(assets.resolve("exodus.json"));EndpointContextRules rules=new EndpointContextRules(catalog);
        JSONObject e=event("mtalk.google.com"),r=analyze(rules,e);String original=e.toString();
        check(firstService(r).getString("id").equals("fcm-device-transport"),"FCM transport not recognized");
        check(r.getString("summary").contains("Notifications"),"FCM purpose absent");
        check(r.getString("local_recipient_status").equals("NOT_OBSERVED"),"shared UID invented app recipient");
        check(r.getString("payload_status").equals("NOT_OBSERVED"),"service name invented packet content");
        check(r.getJSONObject("chronology_and_volume").getLong("rx_bytes")==40,"return bytes lost");
        check(r.getJSONArray("source_event_ids").getLong(0)==7,"source event absent");
        analyze(rules,e);check(e.toString().equals(original),"interpretation rewrote observation");
        JSONObject historical=event("mtalk.google.com").put("timestamp_ms",946684800000L);r=analyze(rules,historical);
        check(r.getLong("observed_event_ms")==946684800000L&&r.getLong("analysis_generated_ms")>946684800000L,"historical event redated as analysis");
        historical.put("network_context",r);check(EndpointContextRules.explain(historical).contains("Événement :")&&EndpointContextRules.explain(historical).contains("Analyse :"),"historical enrichment hides separate dates");
        check(firstService(analyze(rules,event("MTALK.GOOGLE.COM."))).getString("id").equals("fcm-device-transport"),"canonical hostname mismatch");
        check(firstService(analyze(rules,event("alt8-mtalk.google.com"))).getString("id").equals("fcm-device-transport"),"alternate transport host absent");
        check(analyze(rules,event("mtalk.google.com.attacker.test")).getJSONArray("services").length()==0,"suffix impersonation accepted");
        check(analyze(rules,event("notmtalk.google.com")).getJSONArray("services").length()==0,"substring impersonation accepted");
        JSONObject ip=event("");ip.getJSONObject("details").put("port",5228);r=analyze(rules,ip);
        check(r.getJSONArray("services").length()==0&&r.getJSONObject("endpoint").getString("evidence").equals("IP_ONLY"),"IP or port alone became Firebase");
        check(r.getString("summary").contains("identifier"),"unknown destination dropped");
        JSONObject missing=event("");missing.getJSONObject("details").remove("rx_bytes");r=analyze(rules,missing);
        check(!r.getJSONObject("chronology_and_volume").has("rx_bytes"),"missing counter became zero");
        check(firstService(analyze(rules,event("firebaseinstallations.googleapis.com"))).getString("category").equals("installation_identity"),"installation identity became Analytics");
        check(firstService(analyze(rules,event("firebaseremoteconfig.googleapis.com"))).getString("category").equals("remote_configuration"),"remote configuration unclassified");
        check(firstService(analyze(rules,event("demo.firebaseio.com"))).getString("category").equals("database"),"database host unclassified");
        check(firstService(analyze(rules,event("demo.europe-west1.firebasedatabase.app"))).getString("category").equals("database"),"regional database unclassified");
        check(analyze(rules,event("demo.firebaseio.com.attacker.test")).getJSONArray("services").length()==0,"database spoof accepted");
        check(firstService(analyze(rules,event("firestore.googleapis.com"))).getString("id").equals("firestore"),"Firestore unclassified");
        for(String h:new String[]{"firebaselogging.googleapis.com","firebaselogging-pa.googleapis.com"}){
            r=analyze(rules,event(h));check(firstService(r).getString("id").equals("firebase-logging-transport"),"Firebase logging confused with notification transport");
            check(!r.getString("summary").contains("Notifications")&&r.getString("payload_status").equals("NOT_OBSERVED"),"logging service invented notification content");
        }
        check(analyze(rules,event("firebaselogging.googleapis.com.attacker.test")).getJSONArray("services").length()==0,"logging host spoof accepted");
        JSONObject localDns=event("");JSONObject ld=localDns.getJSONObject("details");ld.put("remote_ip","192.168.1.1").put("port",53).put("protocol","UDP").put("observation_type","IDENTITY_ENRICHMENT").put("tx_bytes",JSONObject.NULL).put("rx_bytes",JSONObject.NULL).put("outbound_observed",false).put("inbound_observed",false);
        String dnsRaw=localDns.toString();r=analyze(rules,localDns);
        check(firstService(r).getString("id").equals("dns-transport-candidate")&&firstService(r).getString("connection_status").equals("CANDIDATE"),"DNS port inferred as confirmed protocol");
        check(r.getString("summary").startsWith("DNS probable")&&r.getJSONObject("endpoint").getString("evidence").equals("IP_PORT_PROTOCOL"),"local DNS compatibility remains unexplained");
        check(r.getJSONObject("observation_context").getBoolean("identity_enrichment_only")&&r.getJSONObject("observation_context").getString("exchange_status").equals("NO_DIRECTION_RECORDED"),"identity enrichment invented an exchange");
        check(r.getJSONObject("chronology_and_volume").isNull("tx_bytes")&&localDns.toString().equals(dnsRaw),"unknown counters or original event rewritten");
        ld.put("protocol","TCP");check(firstService(analyze(rules,localDns)).getString("connection_status").equals("CANDIDATE"),"TCP DNS compatibility lost");
        ld.put("protocol","ICMP");check(analyze(rules,localDns).getJSONArray("services").length()==0,"non DNS transport matched port alone");
        ld.put("protocol","UDP").put("port",5353);check(analyze(rules,localDns).getJSONArray("services").length()==0,"other port promoted to unicast DNS");
        ld.put("port",53).put("tx_packets",1).put("rx_packets",1);r=analyze(rules,localDns);
        check(r.getJSONObject("observation_context").getString("exchange_status").equals("BIDIRECTIONAL_OBSERVED")&&!r.getJSONObject("observation_context").getBoolean("identity_enrichment_only"),"actual direction observations lost");
        check(firstService(analyze(rules,event("fcm.googleapis.com"))).getString("id").equals("fcm-send-api"),"send API confused with transport");
        JSONObject dns=event("").put("category","dns");dns.getJSONObject("details").put("question","mtalk.google.com");r=analyze(rules,dns);
        check(r.getString("summary").startsWith("Recherche DNS"),"DNS inquiry became connection");
        check(firstService(r).getString("connection_status").equals("CANDIDATE")&&r.getString("interpretation").contains("aucune connexion"),"DNS promoted to observed endpoint");
        JSONObject outer=event("mtalk.google.com");outer.getJSONObject("details").put("ech_extension_present",true);r=analyze(rules,outer);
        check(firstService(r).getString("connection_status").equals("CANDIDATE")&&r.getString("summary").startsWith("Nom TLS externe"),"outer ECH name promoted to inner service");
        JSONArray trackers=exodus.getJSONArray("trackers");List<TrackerMatcher.Signature> signatures=new ArrayList<>();Map<Integer,JSONObject> rows=new HashMap<>();int networks=0;
        for(int i=0;i<trackers.length();i++){JSONObject t=trackers.getJSONObject(i);rows.put(t.getInt("id"),t);signatures.add(new TrackerMatcher.Signature(t.getInt("id"),t.getString("name"),t.optString("network_signature"),t.optString("code_signature")));if(!t.optString("network_signature").isEmpty())networks++;}
        check(trackers.length()==432&&networks==260,"bundled Exodus catalogue changed");
        TrackerMatcher matcher=new TrackerMatcher(signatures);JSONArray matches=new JSONArray();
        JSONArray fcmMatches=new JSONArray();for(TrackerMatcher.Hit h:matcher.network("mtalk.google.com"))fcmMatches.put(EndpointContextRules.object("tracker_id",h.signature.id,"name",h.signature.name,"categories",rows.get(h.signature.id).getJSONArray("categories"),"domain_boundary_match",h.boundary));
        r=rules.analyze(event("mtalk.google.com"),fcmMatches,new JSONArray());
        check(r.getJSONArray("tracker_candidates").length()>0&&!r.getString("summary").contains("Google Ads"),"generic Google signature labels FCM advertising");
        JSONObject fcmEvent=event("mtalk.google.com").put("network_context",r);
        check(!EndpointContextRules.trackersBrief(fcmEvent).contains("Google Ads")&&EndpointContextRules.trackersBrief(fcmEvent).contains("partielle"),"tracker column still promotes broad Google signature");
        check(EndpointContextRules.explain(fcmEvent).contains("Google Ads")&&EndpointContextRules.explain(fcmEvent).contains("Correspondance partielle"),"partial Exodus evidence silently discarded");
        JSONObject support=event("android.apis.google.com");support.getJSONObject("details").put("ech_extension_present",true);r=analyze(rules,support);
        check(r.getString("summary").startsWith("Nom TLS externe")&&r.getString("summary").contains("possible"),"compact support label hides ECH ambiguity");
        for(TrackerMatcher.Hit h:matcher.network("firebaselogging-pa.googleapis.com"))matches.put(EndpointContextRules.object("tracker_id",h.signature.id,"name",h.signature.name,"categories",rows.get(h.signature.id).getJSONArray("categories"),"domain_boundary_match",h.boundary));
        r=rules.analyze(event("firebaselogging-pa.googleapis.com"),matches,new JSONArray());
        check(r.getString("summary").contains("Analyse d’usage"),"Exodus purpose missing");
        check(r.getJSONArray("tracker_candidates").getJSONObject(0).getString("usage_status").equals("CANDIDATE"),"destination match proved SDK execution");
        check(!r.getJSONArray("tracker_candidates").getJSONObject(0).getBoolean("content_observed"),"tracker category became content");
        JSONArray partial=new JSONArray().put(EndpointContextRules.object("tracker_id",49,"name","Google Firebase Analytics","categories",new JSONArray().put("Analytics"),"domain_boundary_match",false));
        check(rules.analyze(event("evil.test"),partial,new JSONArray()).getString("summary").contains("partielle"),"partial signature promoted");
        dns.getJSONObject("details").put("question","firebaselogging-pa.googleapis.com");
        check(rules.analyze(dns,matches,new JSONArray()).getString("summary").startsWith("Recherche DNS"),"tracker-only DNS match became connection");
        JSONObject pc=EndpointContextRules.object("observations",new JSONArray().put(EndpointContextRules.object("label","Contacts","relation","PROXIMITE_TEMPORELLE")));
        e.put("permission_context",pc);r=analyze(rules,e);
        check(r.getJSONObject("permission_context").getJSONArray("observations").length()==1&&r.getString("payload_status").equals("NOT_OBSERVED"),"nearby access became exfiltration");
        e.put("network_context",r);JSONObject aggregate=EndpointContextRules.aggregate(new JSONArray().put(e).put(event("").put("network_context",analyze(rules,event("")))));
        check(aggregate.getJSONArray("events").length()==2&&aggregate.getJSONArray("events").getJSONObject(0).getLong("source_event_id")==7,"anomaly lost evidence references");
        check(!EndpointContextRules.isNetwork(EndpointContextRules.object("category","acces","details",EndpointContextRules.object("permission","android.permission.READ_CONTACTS"))),"internal access became network");
        check(EndpointContextRules.explain(e).contains("Exodus")==false&&EndpointContextRules.explain(e).contains("UID partagé"),"detail lacks scope");
        boolean rejected=false;try{new EndpointContextRules(EndpointContextRules.object("schema","wrong"));}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"wrong catalogue schema accepted");
        System.out.println("Endpoint context: "+checks+" checks passed");
    }
}
