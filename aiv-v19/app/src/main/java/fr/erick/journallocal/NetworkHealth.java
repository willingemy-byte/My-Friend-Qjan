package fr.erick.journallocal;
import android.content.Context;
import android.net.*;
import android.os.SystemClock;
import org.json.*;
/** Passive network validation, independent from VPN relay and collector heartbeat. */
final class NetworkHealth {
    private final ConnectivityManager manager;private final EventStore store;
    private String previous="";private long unavailableAt=-1,unavailableWall=0,previousSample=-1;private boolean intervalHasGap=false;
    NetworkHealth(Context c){manager=c.getSystemService(ConnectivityManager.class);store=EventStore.get(c);}
    JSONObject sample(){
        long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();if(previousSample>=0&&elapsed-previousSample>150000)intervalHasGap=true;previousSample=elapsed;
        try{
            JSONArray networks=new JSONArray();boolean validated=false,physical=false;
            for(Network network:manager.getAllNetworks()){
                NetworkCapabilities c=manager.getNetworkCapabilities(network);if(c==null||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;
                physical=true;boolean v=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);validated|=v;
                String transport=c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Cellulaire":c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"Autre";
                LinkProperties lp=manager.getLinkProperties(network);networks.put(EventStore.object("network_id",network.toString(),"transport",transport,"validated_by_android",v,"captive_portal",c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),"interface",lp==null?JSONObject.NULL:lp.getInterfaceName()));
            }
            String state=validated?"internet_valide_par_android":physical?"internet_non_valide_par_android":"aucun_reseau_physique_internet";Network active=manager.getActiveNetwork();NetworkCapabilities a=active==null?null:manager.getNetworkCapabilities(active);
            JSONObject details=EventStore.object("state",state,"physical_networks",networks,"default_network",active==null?JSONObject.NULL:active.toString(),"default_is_vpn",a!=null&&a.hasTransport(NetworkCapabilities.TRANSPORT_VPN),"default_validated",a!=null&&a.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                "vpn_running",NetworkCaptureService.running,"vpn_state",NetworkCaptureService.stateText,"vpn_error",NetworkCaptureService.lastError,"probe_sent_by_journal",false,"scope","État Android à cet instant; disponibilité d’un site précis et cause d’une panne non déterminées");
            if(!state.equals(previous)){
                if(!validated&&unavailableAt<0){unavailableAt=elapsed;unavailableWall=wall;intervalHasGap=false;}
                details.put("previous_state",previous.isEmpty()?"premier_releve":previous);
                if(validated&&unavailableAt>=0){details.put("unvalidated_since_observed_ms",unavailableWall).put("interval_between_observations_ms",elapsed-unavailableAt).put("sampling_gap_in_interval",intervalHasGap).put("interval_interpretation","Durée entre relevés; ne garantit pas une coupure ininterrompue ni son heure exacte de début");unavailableAt=-1;}
                store.add("reseau","Android",previous.isEmpty()?"État initial de disponibilité Internet":validated?"Internet de nouveau validé par Android":"Internet non validé par Android","Santé des réseaux","Global","ConnectivityManager — relevé passif",details);previous=state;
            }return details;
        }catch(Exception e){intervalHasGap=true;return EventStore.object("state","inconnu","error",e.getClass().getSimpleName(),"vpn_running",NetworkCaptureService.running);}
    }
}