package fr.erick.journallocal;

import java.util.*;
import org.json.*;

/** Bounded, monotonic front/back correlation. Findings distinguish observation from inference. */
final class ConvergenceRules {
    private final ArrayDeque<JSONObject> recent=new ArrayDeque<>();
    private final LinkedHashSet<String> emitted=new LinkedHashSet<>();
    static JSONObject details(JSONObject e){JSONObject d=e.optJSONObject("details");return d==null?e:d;}
    static JSONObject frontend(JSONObject e){try{return new JSONObject(details(e).toString()).put("clock_scope_id",e.optString("clock_scope_id"));}catch(JSONException error){throw new IllegalArgumentException("Snapshot frontend invalide",error);}}
    static String actorKey(JSONObject e){
        JSONObject d=details(e),id=d.optJSONObject("app_identity");JSONArray pkgs=d.optJSONArray("packages");int uid=d.optInt("uid",-1);
        if(id==null||uid<0||uid%100000<10000||pkgs==null||pkgs.length()!=1||d.optBoolean("identity_conflict")||id.optInt("uid",-1)!=uid||!pkgs.optString(0).equals(id.optString("package_name"))||id.optString("app_identity_id").isEmpty()||id.optJSONArray("current_signer_sha256")==null||id.optJSONArray("current_signer_sha256").length()==0||id.optLong("first_install_ms")<=0||id.optLong("version_code",-1)<0||id.has("network_attribution_unique")&&!id.optBoolean("network_attribution_unique"))return "";
        return pkgs.optString(0)+"|"+uid+"|"+id.optString("app_identity_id")+"|"+id.optLong("version_code",-1)+"|"+id.optLong("first_install_ms");
    }
    static long delta(JSONObject a,JSONObject b){
        String scope=a.optString("clock_scope_id");long x=a.optLong("elapsed_ms",-1),y=b.optLong("elapsed_ms",-1);
        return !scope.isEmpty()&&scope.equals(b.optString("clock_scope_id"))&&x>=0&&y>=0?x-y:Long.MAX_VALUE;
    }
    static boolean sameNear(JSONObject a,JSONObject b,long window){String k=actorKey(a);long d=delta(a,b);return !k.isEmpty()&&k.equals(actorKey(b))&&d!=Long.MAX_VALUE&&Math.abs(d)<=window;}
    JSONObject interactionFor(JSONObject event){
        for(Iterator<JSONObject> it=recent.descendingIterator();it.hasNext();){JSONObject click=it.next();JSONObject d=details(click);long age=delta(event,click);
            if(!"frontend".equals(click.optString("category"))||!"CLICK".equals(d.optString("event_kind"))||!"EVENT_NODE".equals(d.optString("element_source"))||age<0||age>3000||!sameNear(event,click,3000))continue;
            JSONObject element=d.optJSONObject("element");JSONArray b=element==null?null:element.optJSONArray("bounds");
            if(b!=null&&b.length()==4&&element.optBoolean("visible")&&b.optInt(2)>b.optInt(0)&&b.optInt(3)>b.optInt(1))return click;
        }return null;
    }
    static JSONObject profile(JSONObject e,boolean known,boolean official){
        JSONObject d=details(e);String host=AnomalyRules.domain(d.optString("tls_sni"));boolean dns="dns".equals(e.optString("category"));
        if(dns)host=AnomalyRules.domain(d.optString("question"));String owner=actorKey(e);boolean outer=d.optBoolean("ech_extension_present");
        String state=d.optBoolean("identity_conflict")?"DIVERGENT":owner.isEmpty()?"UNRESOLVED":host.isEmpty()?"SHARED_INFRASTRUCTURE":official&&!outer&&!dns?"OFFICIAL_IDENTIFIED":known?"KNOWN_FOR_APP":"NEW_FOR_APP";
        return EventStore.object("schema","aiv-endpoint-pedigree/1","status",state,"history_status",known?"KNOWN_FOR_APP":"NEW_FOR_APP","actor_key",owner,"host",host,"ip",d.optString("remote_ip"),"port",d.optInt("port"),"protocol",d.optString("protocol"),"evidence",dns?"DNS_QUERY_ONLY":host.isEmpty()?"IP_ONLY":outer?"TLS_OUTER_NAME":"TLS_SNI","scope","Connu ne signifie pas sûr. Une IP partagée ou une question DNS ne démontre pas le service contacté; le contenu et le destinataire applicatif derrière un service restent inconnus.");
    }
    List<JSONObject> accept(JSONObject event){
        List<JSONObject> found=new ArrayList<>();String category=event.optString("category");
        if(!Arrays.asList("frontend","acces","trafic","dns").contains(category))return found;
        // Reserve room for each sensor: packet counter bursts must not evict the last click/AppOp.
        int count=0;for(JSONObject e:recent)if(category.equals(e.optString("category")))count++;
        int cap="frontend".equals(category)?24:"acces".equals(category)?40:"dns".equals(category)?8:64;
        if(count>=cap)for(Iterator<JSONObject> it=recent.iterator();it.hasNext();)if(category.equals(it.next().optString("category"))){it.remove();break;}
        recent.addLast(event);while(recent.size()>136)recent.removeFirst();
        JSONObject pedigree=event.optJSONObject("endpoint_pedigree");
        if("trafic".equals(category)&&pedigree!=null&&"NEW_FOR_APP".equals(pedigree.optString("status"))&&pedigree.optLong("actor_sessions")>=3&&!pedigree.optString("host").isEmpty()&&details(event).optBoolean("outbound_observed")){
            for(Iterator<JSONObject> it=recent.descendingIterator();it.hasNext();){JSONObject click=it.next();if("CLICK".equals(details(click).optString("event_kind"))&&sameNear(event,click,3000)){emit(found,"new-endpoint",click,event,null,"Endpoint nouveau après une interaction","Un hôte nouveau pour cette identité/version apparaît près du clic. L’historique indique une nouveauté, sans conclure à un danger ni à la cause de cet échange.");break;}}
        }
        if("frontend".equals(category)&&Arrays.asList("WINDOW_CHANGE","PACKAGE_CHANGE").contains(details(event).optString("event_kind"))){
            for(Iterator<JSONObject> it=recent.descendingIterator();it.hasNext();){JSONObject click=it.next();JSONObject cd=details(click);long d=delta(event,click);
                if(d<0||d>3000||!"CLICK".equals(cd.optString("event_kind")))continue;
                String label=cd.optJSONObject("element")==null?"":cd.optJSONObject("element").optString("text").toLowerCase(Locale.ROOT);
                String pkg=details(event).optString("package_name");if((label.contains("télécharg")||label.contains("download"))&&(pkg.equals("com.android.chrome")||pkg.equals("org.mozilla.firefox"))&&!pkg.equals(cd.optString("package_name"))){
                    emit(found,"action-browser",click,event,null,"Action de téléchargement : navigateur ouvert","Le clic libellé téléchargement et l’ouverture d’un navigateur sont observés. La poursuite du téléchargement n’est pas observable à ce stade.");break;
                }
            }
        }
        if("acces".equals(category)){
            JSONObject od=details(event);String operation=od.optString("operation");
            if("ACCESS".equals(od.optString("access_result"))&&WatcherContextRules.sensitive(operation)){
                JSONObject nearest=null;long nearestAbs=Long.MAX_VALUE;boolean sameActor=false,userInteraction=false,sameClock=false;
                for(Iterator<JSONObject> it=recent.descendingIterator();it.hasNext();){
                    JSONObject front=it.next();if(!"frontend".equals(front.optString("category")))continue;
                    long d=delta(event,front);if(d==Long.MAX_VALUE||Math.abs(d)>5000)continue;
                    JSONObject fd=details(front);String kind=fd.optString("event_kind");
                    boolean interaction=WatcherContextRules.userEvent(kind);
                    if(!interaction&&!"WINDOW_CHANGE".equals(kind)&&!"PACKAGE_CHANGE".equals(kind))continue;
                    long abs=Math.abs(d);if(abs<nearestAbs){nearest=front;nearestAbs=abs;sameClock=true;userInteraction=interaction;sameActor=!actorKey(event).isEmpty()&&actorKey(event).equals(actorKey(front));}
                }
                WatcherContextRules.State state=WatcherContextRules.classify(operation,sameClock,nearest!=null,sameActor,userInteraction);
                emitWatcher(found,event,nearest,state,operation);
            }
        }

        // Do not report a mere permission grant, a rejection, or an unidentified/shared socket.
        for(JSONObject network:recent){if(!"trafic".equals(network.optString("category"))||actorKey(network).isEmpty())continue;
            JSONObject nd=details(network);if(!nd.optBoolean("outbound_observed")&&!nd.optBoolean("inbound_observed"))continue;
            for(JSONObject op:recent){JSONObject od=details(op);String name=od.optString("operation");
                if(!"acces".equals(op.optString("category"))||!"ACCESS".equals(od.optString("access_result"))||!Arrays.asList("READ_CONTACTS","READ_SMS","RECORD_AUDIO","CAMERA","FINE_LOCATION").contains(name)||!sameNear(network,op,3000))continue;
                for(Iterator<JSONObject> it=recent.descendingIterator();it.hasNext();){JSONObject click=it.next();
                    if(!"CLICK".equals(details(click).optString("event_kind"))||!sameNear(network,click,3000)||!sameNear(op,click,3000))continue;
                    emit(found,"sensitive-action-network",click,network,op,"Accès sensible près d’une interaction réseau",od.optString("label",name)+" et un flux de la même application sont observés près du clic. Leur lien fonctionnel et le contenu transmis restent inconnus.");break;
                }
            }
        }
        return found;
    }
    private void emitWatcher(List<JSONObject> result,JSONObject access,JSONObject frontend,WatcherContextRules.State state,String operation){
        JSONObject ad=details(access),fd=frontend==null?null:details(frontend);
        long delta=frontend==null?Long.MAX_VALUE:delta(access,frontend);
        String actor=access.optString("app"),title,explanation,severity="information",kind="trace";
        switch(state){
            case USER_CORROBORATED:
                title="Action utilisateur corroborée";
                explanation=ad.optString("label",operation)+" est observé pour "+actor+" près d’une interaction du même acteur sur la même horloge monotone.";
                break;
            case CONTRADICTORY:
                title="Action backend incohérente avec l’écran";
                explanation=ad.optString("label",operation)+" est observé pour "+actor+" alors qu’une interaction utilisateur proche concerne une autre application.";
                severity="attention";kind="anomaly";break;
            case UNEXPLAINED:
                title="Action backend non expliquée";
                explanation=ad.optString("label",operation)+" est observé sans interaction utilisateur correspondante dans la fenêtre de corrélation.";
                severity="attention";break;
            case UNKNOWN:
                title="Contexte utilisateur inconnu";
                explanation=ad.optString("label",operation)+" est observé mais l’horloge ou le contexte frontend ne permet pas une corrélation fiable.";
                break;
            default:
                title="Action backend attendue";
                explanation=ad.optString("label",operation)+" est observé sans exigence d’interaction utilisateur explicite.";
        }
        JSONArray ids=new JSONArray().put(access.optLong("id"));if(frontend!=null)ids.put(frontend.optLong("id"));
        JSONObject facts=EventStore.object(
            "operation",operation,
            "watcher_state",state.name(),
            "same_actor",frontend!=null&&!actorKey(access).isEmpty()&&actorKey(access).equals(actorKey(frontend)),
            "frontend_event_kind",fd==null?JSONObject.NULL:fd.optString("event_kind"),
            "delta_ms",delta==Long.MAX_VALUE?JSONObject.NULL:delta,
            "time_basis","ANDROID_MONOTONIC",
            "content_observed",ad.optBoolean("content_observed",false),
            "sent_content_observed",ad.optBoolean("sent_content_observed",false)
        );
        String key="watcher:"+operation+":"+access.optLong("id");
        JSONObject out=EventStore.object(
            "group_key","live:"+key,"rule","watcher-context","kind",kind,"category","WATCHER_DECISION","severity",severity,
            "title",title,"actor",actor,"subject",ad.optString("label",operation),"explanation",explanation,
            "advice",state==WatcherContextRules.State.CONTRADICTORY?"Examiner le contexte frontend et l’accès AppOps avant toute action.":"Aucune action automatique; conserver la corrélation comme preuve.",
            "facts",facts,"evidence_ids",ids,"first_ms",frontend==null?access.optLong("timestamp_ms"):Math.min(frontend.optLong("timestamp_ms"),access.optLong("timestamp_ms")),
            "last_ms",access.optLong("timestamp_ms"),"last_event_id",access.optLong("id"),
            "conclusion",EventStore.object("established",new JSONArray().put("Accès AppOps observé"),"correlated",frontend==null?new JSONArray():new JSONArray().put("Événement frontend rapproché sur horloge monotone"),"unknown",new JSONArray().put("Contenu de l’accès").put("Causalité fonctionnelle exacte"),"confidence",frontend==null?"OBSERVED_ONLY":"OBSERVED_AND_TEMPORALLY_CORRELATED")
        );
        if(frontend!=null)try{
            out.put("frontend",frontend(access,frontend)).put("timeline",new JSONArray()
                .put(EventStore.object("event_id",frontend.optLong("id"),"elapsed_ms",frontend.optLong("elapsed_ms"),"action",frontend.optString("action"),"source",frontend.optString("source")))
                .put(EventStore.object("event_id",access.optLong("id"),"elapsed_ms",access.optLong("elapsed_ms"),"action",access.optString("action"),"source",access.optString("source"))));
        }catch(Exception ignored){}
        result.add(out);
    }

    private static JSONObject frontend(JSONObject access,JSONObject front){
        try{return new JSONObject(details(front).toString()).put("clock_scope_id",front.optString("clock_scope_id")).put("relation","TEMPORAL_CONTEXT").put("delta_ms",delta(access,front));}
        catch(Exception e){return new JSONObject();}
    }

    private void emit(List<JSONObject> result,String rule,JSONObject click,JSONObject event,JSONObject op,String title,String explanation){
        String key=rule+":"+click.optLong("id")+":"+(op==null?event.optLong("id"):details(op).optString("usage_observation_id"));if(!emitted.add(key))return;while(emitted.size()>512)emitted.remove(emitted.iterator().next());
        JSONArray timeline=new JSONArray();List<JSONObject> ordered=new ArrayList<>(Arrays.asList(click,event));if(op!=null)ordered.add(op);ordered.sort(Comparator.comparingLong(e->e.optLong("elapsed_ms")));
        JSONArray ids=new JSONArray();for(JSONObject e:ordered){ids.put(e.optLong("id"));timeline.put(EventStore.object("event_id",e.optLong("id"),"wall_ms",e.optLong("timestamp_ms"),"elapsed_ms",e.optLong("elapsed_ms"),"clock_scope_id",e.optString("clock_scope_id"),"source",e.optString("source"),"action",e.optString("action")));}
        JSONObject d=frontend(click);String correlation=java.util.UUID.randomUUID().toString();
        result.add(EventStore.object("group_key","live:"+key,"rule",rule,"kind","anomaly","category","APPLICATION_BEHAVIOR","severity","attention","title",title,"actor",click.optString("app"),"subject",event.optString("destination"),"explanation",explanation,"correlation_id",correlation,"evidence_ids",ids,"identity",EventStore.object("app",click.optString("app"),"details",d),"frontend",d,"appops",op==null?JSONObject.NULL:details(op),"network",details(event),"timeline",timeline,"conclusion",EventStore.object("established",new JSONArray().put("Observations Android conservées dans les événements sources"),"correlated",new JSONArray().put("Proximité sur la même horloge monotone; identité vérifiée pour le rapprochement AppOps/réseau"),"unknown",new JSONArray().put("Contenu transmis").put("Causalité du clic").put("Livraison à une application derrière un service"),"confidence","OBSERVED_AND_TEMPORALLY_CORRELATED"),"visual",EventStore.object("status","SEMANTIC_ONLY","comparison_confirmed",false),"first_ms",click.optLong("timestamp_ms"),"last_ms",event.optLong("timestamp_ms"),"last_event_id",event.optLong("id")));
    }
}
