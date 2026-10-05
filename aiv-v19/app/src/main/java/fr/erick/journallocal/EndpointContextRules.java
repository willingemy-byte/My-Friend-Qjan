package fr.erick.journallocal;

import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Derived, offline interpretations. Never changes an observation or guesses a local recipient. */
final class EndpointContextRules {
    private final JSONObject catalogue;
    private final List<Service> services=new ArrayList<>();
    private static final class Service {
        final JSONObject row;final Set<String> hosts=new HashSet<>();final List<Pattern> patterns=new ArrayList<>();final Set<String> protocols=new HashSet<>();final int candidatePort,priority;
        Service(JSONObject r)throws Exception{
            row=r;priority=r.optInt("priority",100);JSONObject match=r.getJSONObject("match");JSONArray names=match.getJSONArray("exact_hosts"),regex=match.getJSONArray("full_host_regex");
            for(int i=0;i<names.length();i++)hosts.add(TrackerMatcher.hostname(names.getString(i)));
            for(int i=0;i<regex.length();i++)patterns.add(Pattern.compile(regex.getString(i),Pattern.CASE_INSENSITIVE));
            JSONObject candidate=match.optJSONObject("transport_candidate");candidatePort=candidate==null?-1:candidate.optInt("port",-1);
            JSONArray transports=candidate==null?null:candidate.optJSONArray("protocols");if(transports!=null)for(int i=0;i<transports.length();i++)protocols.add(transports.getString(i));
        }
        boolean matches(String host){if(host.isEmpty())return false;if(hosts.contains(host))return true;for(Pattern p:patterns)if(p.matcher(host).matches())return true;return false;}
        boolean transportCandidate(int port,String protocol){return candidatePort>0&&candidatePort==port&&protocols.contains(protocol);}
    }
    EndpointContextRules(JSONObject c)throws Exception{
        if(!"aiv-endpoint-services/1".equals(c.optString("schema")))throw new IllegalArgumentException("Catalogue de services inconnu");
        catalogue=new JSONObject(c.toString());JSONArray rows=catalogue.getJSONArray("service_rules");
        if(rows.length()>256)throw new IllegalArgumentException("Catalogue trop grand");
        for(int i=0;i<rows.length();i++)services.add(new Service(rows.getJSONObject(i)));
    }
    JSONObject metadata(){return object("schema",catalogue.optString("schema"),"version",catalogue.optString("version"),"sources",catalogue.optJSONArray("sources"));}
    static JSONObject object(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put(String.valueOf(values[i]),values[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
    static JSONObject details(JSONObject e){JSONObject d=e.optJSONObject("details");return d==null?e:d;}
    static boolean isNetwork(JSONObject e){JSONObject d=details(e);return !d.optString("flow_correlation_id").isEmpty()||!d.optString("remote_ip").isEmpty()||"dns".equals(e.optString("category"));}
    static String categories(JSONArray categories){
        LinkedHashSet<String> labels=new LinkedHashSet<>();if(categories!=null)for(int i=0;i<categories.length();i++){
            String c=categories.optString(i),label=c;
            if("Analytics".equals(c))label="Analyse d’usage";else if("Advertisement".equals(c))label="Publicité";
            else if("Crash reporting".equals(c))label="Rapports de plantage";else if("Profiling".equals(c))label="Profilage";
            else if("Location".equals(c))label="Localisation";else if("Identification".equals(c))label="Identification";
            if(!label.isEmpty())labels.add(label);
        }return join(labels," / ");
    }
    private static String join(Collection<String> labels,String separator){StringBuilder s=new StringBuilder();for(String v:labels){if(s.length()>0)s.append(separator);s.append(v);}return s.toString();}
    private static JSONObject ownerRelation(JSONObject e,JSONObject d,JSONArray found,String host,String evidence,boolean query,boolean outer){
        int uid=d.optInt("uid",-1);JSONArray packages=d.optJSONArray("packages");JSONObject identity=d.optJSONObject("app_identity");
        String pkg=packages!=null&&packages.length()==1?packages.optString(0):"";
        boolean unique=uid>=0&&uid%100000>=10000&&!pkg.isEmpty()&&!d.optBoolean("identity_conflict");
        if(identity!=null&&(!identity.optString("package_name",pkg).equals(pkg)||identity.optInt("uid",uid)!=uid))unique=false;
        String actor=e.optString("app",d.optString("app",pkg));if(actor.isEmpty())actor=pkg;
        JSONArray links=new JSONArray();for(int i=0;i<found.length();i++){
            JSONObject r=found.optJSONObject(i);if(r!=null)links.put(object("service_id",r.optString("id"),"service",r.optString("display_service"),"role_status",r.optString("role_status"),"function_status","INFERENCE_REQUIRED","source_ids",r.optJSONArray("source_ids")));
        }
        String text=unique?"Propriétaire du flux : "+actor+" ("+pkg+", UID "+uid+").":"Application propriétaire non déterminée de façon unique; aucun paquet précis n’est choisi derrière un service ou un UID partagé.";
        String status=unique?"OWNER_ONLY":"OWNER_UNRESOLVED";
        if(found.length()>0){
            String label=found.optJSONObject(0).optString("display_service");
            if(unique){status=query||outer||host.isEmpty()?"CANDIDATE_OWNER_SERVICE_LINK":"OBSERVED_OWNER_HOST_LINK";
                text+=host.isEmpty()?" Destination : "+d.optString("remote_ip")+":"+d.optInt("port",d.optInt("remote_port",-1))+". Service compatible : "+label+".":(query?" Nom recherché":outer?" Nom TLS externe annoncé":" Nom TLS annoncé")+" : "+host+". Service associé : "+label+".";
                text+=" Fonction compatible : "+found.optJSONObject(0).optString("display_purpose")+". La fonction dans cet échange reste une déduction.";
            }else text+=" Service associé aux indices réseau : "+label+".";
        }else if(unique)text+=" Fonction de la destination encore indéterminée.";
        return object("schema","aiv-owner-service-relation/1","status",status,"network_owner_uid",uid>=0?uid:JSONObject.NULL,"owner_package",unique?pkg:JSONObject.NULL,"owner_attribution_unique",unique,
            "app_identity_id",unique&&identity!=null?identity.opt("app_identity_id"):JSONObject.NULL,"network_actor_identity_id",unique&&identity!=null?identity.opt("network_actor_identity_id"):JSONObject.NULL,
            "host",host,"evidence",evidence,"service_links",links,"interpretation",text,"originating_application_status","NOT_ESTABLISHED_BY_THIS_LINK","local_delivery_status","NOT_OBSERVED",
            "scope","Rapprochement du propriétaire réseau et des indices de destination du même flux. Il n’identifie pas une application demandeuse derrière un service système et ne prouve ni le contenu, ni la livraison applicative du retour.");
    }
    JSONObject analyze(JSONObject e,JSONArray trackerMatches,JSONArray dnsCandidates)throws Exception{
        JSONObject d=details(e);boolean query="dns".equals(e.optString("category"))&&!d.optString("question").isEmpty();
        boolean outer=!query&&d.optBoolean("ech_extension_present");
        String host=TrackerMatcher.hostname(query?d.optString("question"):d.optString("tls_sni"));
        String evidence=query?"DNS_QUERY_ONLY":outer?"TLS_OUTER_NAME":host.isEmpty()?"IP_ONLY":"TLS_SNI";
        int port=d.optInt("port",d.optInt("remote_port",-1));String protocol=d.optString("protocol",e.optString("protocol")).toUpperCase(Locale.ROOT);
        JSONArray found=new JSONArray(),trackers=new JSONArray(),claims=new JSONArray();LinkedHashSet<String> purposes=new LinkedHashSet<>();boolean transportOnly=false;
        int priority=Integer.MIN_VALUE;for(Service service:services)if(service.matches(host)||!query&&!outer&&host.isEmpty()&&service.transportCandidate(port,protocol))priority=Math.max(priority,service.priority);
        for(Service service:services){
            boolean hostMatch=service.matches(host),candidate=!query&&!outer&&host.isEmpty()&&service.transportCandidate(port,protocol);
            if((!hostMatch&&!candidate)||service.priority!=priority)continue;
            JSONObject r=service.row;String label=r.optString("service"),purpose=r.optString("display_purpose_fr",r.optString("documented_role_fr"));
            boolean uncertain=query||outer||candidate;String matchEvidence=candidate?"IP_PORT_PROTOCOL":evidence;transportOnly|=candidate;
            String assessment=r.optString("assessment_status","DOCUMENTED_SERVICE_ROLE"),roleStatus="DOCUMENTED_SERVICE_ROLE".equals(assessment)?"DOCUMENTED_ROLE":assessment;
            found.put(object("id",r.optString("id"),"service",label,"provider",r.optString("provider"),"display_service",r.optString("display_service_fr",label),"display_purpose",purpose,"documented_role",r.optString("documented_role_fr"),"category",r.optString("functional_category"),"source_ids",r.optJSONArray("source_ids"),"role_status",roleStatus,"flow_purpose_status","INFERENCE_REQUIRED","connection_status",uncertain?"CANDIDATE":"OBSERVED_HOST_MATCH","match_kind",candidate?"TRANSPORT_CANDIDATE":"HOST_MATCH","note",r.optString("interpretation_note_fr")));
            purposes.add(purpose);claims.put(object("claim",label,"status",uncertain?"CANDIDATE":roleStatus,"evidence",matchEvidence,"host",host,"source_ids",r.optJSONArray("source_ids")));
        }
        if(trackerMatches!=null)for(int i=0;i<trackerMatches.length()&&i<32;i++){
            JSONObject original=trackerMatches.optJSONObject(i);if(original==null)continue;JSONObject t=new JSONObject(original.toString());
            String labels=categories(t.optJSONArray("categories"));t.put("purpose_fr",labels).put("usage_status","CANDIDATE").put("content_observed",false).put("summary_eligible",t.optBoolean("domain_boundary_match"));
            trackers.put(t);claims.put(object("claim",t.optString("name"),"status","CANDIDATE","evidence",evidence,"tracker_id",t.optInt("tracker_id"),"categories",t.optJSONArray("categories"),"source",t.optString("source")));
        }
        StringBuilder summary=new StringBuilder();
        if(found.length()>0){summary.append(query?"Recherche DNS · ":outer?"Nom TLS externe · ":"");summary.append(found.getJSONObject(0).optString("display_service")).append("\n").append(join(purposes," / "));}
        int eligible=0,partial=0;
        for(int i=0;i<trackers.length();i++){
            JSONObject t=trackers.getJSONObject(i);if(!t.optBoolean("summary_eligible")){partial++;continue;}eligible++;
            if(eligible>2)continue;if(summary.length()>0)summary.append("\n");else summary.append(query?"Recherche DNS · ":outer?"Nom TLS externe · ":"");
            summary.append("Exodus candidat · ").append(t.optString("name")).append(" · ").append(t.optString("purpose_fr").isEmpty()?"Fonction non catégorisée":t.optString("purpose_fr"));
        }
        if(summary.length()==0)summary.append(query?"Recherche DNS · service à identifier":"Connexion · service à identifier");
        if(eligible>2)summary.append("\n+").append(eligible-2).append(" candidat(s) Exodus · détail");
        if(found.length()==0&&partial>0)summary.append("\n").append(partial).append(" signature(s) partielle(s) · détail");
        String interpretation=found.length()>0?(transportOnly?"Adresse et transport compatibles avec DNS sur le port 53; le protocole et le nom recherché ne sont pas établis sans analyse d’un message DNS.":query?"Nom recherché associé à un service du catalogue; aucune connexion à ce service n’est établie par cette question DNS.":outer?"Nom TLS externe compatible avec un service du catalogue; le nom réel peut être masqué.":"Nom TLS annoncé associé à un service du catalogue; sa fonction dans cet échange reste une déduction."):eligible>0?"Destination correspondant à des signatures Exodus; les catégories indiquent leur fonction connue, pas les données transmises.":"Destination observée; le catalogue ne permet pas encore d’identifier son service.";
        JSONObject relation=ownerRelation(e,d,found,host,transportOnly?"IP_PORT_PROTOCOL":evidence,query,outer);
        boolean outbound=d.optBoolean("outbound_observed")||d.optLong("tx_packets")>0||d.optLong("tx_bytes")>0;
        boolean inbound=d.optBoolean("inbound_observed")||d.optLong("rx_packets")>0||d.optLong("rx_bytes")>0;
        boolean identityOnly="IDENTITY_ENRICHMENT".equals(d.optString("observation_type"))&&!outbound&&!inbound;
        JSONObject observation=object("type",d.optString("observation_type"),"exchange_status",outbound&&inbound?"BIDIRECTIONAL_OBSERVED":outbound?"OUTBOUND_OBSERVED":inbound?"INBOUND_OBSERVED":"NO_DIRECTION_RECORDED","identity_enrichment_only",identityOnly,"scope",identityOnly?"Enrichissement d’identité; aucun paquet aller ou retour ni volume n’est renseigné dans cet événement. Consulter les autres observations du même flux.":"Directions renseignées dans cette observation; leur absence ne signifie pas que le flux n’a jamais transporté de paquet.");
        JSONObject volume=new JSONObject();for(String key:new String[]{"tx_bytes","rx_bytes","tx_packets","rx_packets","outbound_observed","inbound_observed","first_outbound_ms","first_inbound_ms","last_packet_ms"})if(d.has(key))volume.put(key,d.get(key));
        long id=e.optLong("id",e.optLong("latest_event_id",0));JSONArray ids=new JSONArray();if(id>0)ids.put(id);
        long observed=e.optLong("timestamp_ms");if(observed<=0)observed=d.optLong("last_packet_ms",d.optLong("latest_timestamp_ms",0));
        return object("schema","aiv-network-context/1","catalogue_version",catalogue.optString("version"),"analysis_generated_ms",System.currentTimeMillis(),"observed_event_ms",observed>0?observed:JSONObject.NULL,"analysis_time_scope","Analyse à la lecture; l’événement conserve sa date originale.","summary",summary.toString(),"interpretation",interpretation,
            "endpoint",object("host",host,"ip",d.optString("remote_ip"),"port",d.has("port")?d.opt("port"):d.opt("remote_port"),"protocol",protocol,"evidence",transportOnly?"IP_PORT_PROTOCOL":evidence),"observation_context",observation,
            "services",found,"owner_service_relation",relation,"tracker_candidates",trackers,"dns_candidates",dnsCandidates==null?new JSONArray():dnsCandidates,"claims",claims,
            "flow_correlation_id",d.optString("flow_correlation_id"),"source_event_ids",ids,"network_owner_uid",d.has("uid")?d.opt("uid"):JSONObject.NULL,"packages",d.optJSONArray("packages"),"app_identity",d.optJSONObject("app_identity"),"chronology_and_volume",volume,
            "permission_context",e.optJSONObject("permission_context"),"local_recipient_status","NOT_OBSERVED","payload_status","NOT_OBSERVED",
            "scope","L’identité locale relie les observations du flux; elle ne démontre ni le contenu transmis ni la livraison à une application derrière un service ou un UID partagé. Une permission accordée reste une capacité; un accès proche reste un rapprochement temporel.");
    }
    static JSONObject aggregate(JSONArray events)throws Exception{
        JSONArray rows=new JSONArray();LinkedHashSet<String> summaries=new LinkedHashSet<>();
        if(events!=null)for(int i=0;i<events.length();i++){JSONObject e=events.optJSONObject(i),n=e==null?null:e.optJSONObject("network_context");if(n==null)continue;
            rows.put(object("source_event_id",e.optLong("id"),"network_context",n));if(summaries.size()<8)summaries.add(n.optString("summary"));
        }
        return object("schema","aiv-network-anomaly-context/1","summary",join(summaries,"\n"),"events",rows,"scope","Interprétations des événements justificatifs; aucun échange unique ni lien causal supplémentaire n’est déduit de leur regroupement.");
    }
    static String brief(JSONObject e){JSONObject n=e.optJSONObject("network_context");return n==null?"":n.optString("summary");}
    static String trackersBrief(JSONObject e){
        JSONObject n=e.optJSONObject("network_context");JSONArray trackers=n==null?e.optJSONArray("tracker_matches"):n.optJSONArray("tracker_candidates");
        LinkedHashSet<String> labels=new LinkedHashSet<>();int partial=0,eligible=0;
        if(trackers!=null)for(int i=0;i<trackers.length();i++){JSONObject t=trackers.optJSONObject(i);if(t==null)continue;if(!t.optBoolean("domain_boundary_match")){partial++;continue;}if(++eligible<=2)labels.add("Candidat · "+t.optString("name"));}
        if(eligible>2)labels.add("+"+(eligible-2)+" candidat(s) · détail");if(partial>0)labels.add(partial+" signature(s) partielle(s) · détail");
        return labels.isEmpty()?"—":join(labels,"\n");
    }
    static String explain(JSONObject e){
        JSONObject n=e.optJSONObject("network_context");if(n==null)return "";
        StringBuilder s=new StringBuilder(n.optString("interpretation"));
        JSONObject relation=n.optJSONObject("owner_service_relation");if(relation!=null)s.append("\n\n").append(relation.optString("interpretation")).append("\n").append(relation.optString("scope"));
        if(n.optLong("observed_event_ms")>0)s.append("\nÉvénement : ").append(java.text.DateFormat.getDateTimeInstance().format(new Date(n.optLong("observed_event_ms"))));
        if(n.optLong("analysis_generated_ms")>0)s.append("\nAnalyse : ").append(java.text.DateFormat.getDateTimeInstance().format(new Date(n.optLong("analysis_generated_ms")))).append("\n").append(n.optString("analysis_time_scope"));
        JSONObject endpoint=n.optJSONObject("endpoint");if(endpoint!=null){s.append("\nIndice : ").append(endpoint.optString("evidence"));if(!endpoint.optString("host").isEmpty())s.append(" · ").append(endpoint.optString("host"));}
        JSONObject observation=n.optJSONObject("observation_context");if(observation!=null)s.append("\nObservation : ").append(observation.optString("exchange_status")).append("\n").append(observation.optString("scope"));
        JSONArray services=n.optJSONArray("services");if(services!=null)for(int i=0;i<services.length();i++){JSONObject r=services.optJSONObject(i);if(r!=null)s.append("\n\n").append(r.optString("service")).append(" — ").append(r.optString("documented_role")).append("\n").append(r.optString("note"));}
        JSONArray trackers=n.optJSONArray("tracker_candidates");if(trackers!=null)for(int i=0;i<trackers.length();i++){JSONObject t=trackers.optJSONObject(i);if(t!=null){s.append("\n\nExodus #").append(t.optInt("tracker_id")).append(" · ").append(t.optString("name")).append("\nFonction : ").append(t.optString("purpose_fr").isEmpty()?"Non catégorisée":t.optString("purpose_fr"));s.append(t.optBoolean("domain_boundary_match")?"\nSignature de destination candidate; exécution du SDK et contenu non observés.":"\nCorrespondance partielle; elle peut couvrir un domaine général du fournisseur. Le service, la publicité et l’exécution du SDK ne sont pas établis par cette signature.");}}
        s.append("\n\n").append(n.optString("scope"));return s.toString();
    }
}
