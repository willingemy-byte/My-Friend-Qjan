package fr.erick.journallocal;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import org.json.*;

/** Optional local IP relay. No remote VPN gateway, payload persistence or TLS interception. */
public final class NetworkCaptureService extends VpnService {
    public static volatile boolean running=false, starting=false;
    public static volatile String lastError="", stateText="Arrêté";
    private static final String STOP="stop-network";
    private static final SecureRandom FLOW_RANDOM=new SecureRandom();
    private static final String[] STATES={"nouveau","connexion en cours","connecté","fermé","erreur","erreur de socket","erreur côté interface","réinitialisé","injoignable","erreur de relais"};
    private volatile boolean stopped=false, reconfigure=false;
    private volatile String activeConfig="";
    private Handler main;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private Thread engine;
    private EventStore store;
    private Config current;
    private String session="";
    private final HashMap<Long,Flow> flows=new HashMap<>(); // native worker only
    private native int runNative(int fd);

    private static final class Config {
        Network network; List<InetAddress> dns; String key,transport; boolean metered;
    }
    private static String newFlowCorrelationId(){
        byte[] token=new byte[16];FLOW_RANDOM.nextBytes(token);StringBuilder out=new StringBuilder(32);
        for(byte b:token)out.append(String.format(Locale.ROOT,"%02x",b&0xff));return out.toString();
    }
    private static final class Flow {
        final String correlationId=newFlowCorrelationId();
        long firstMs=System.currentTimeMillis(),firstOutboundMs=0,firstInboundMs=0;
        int version,protocol,localPort,remotePort,uid=-1,lookupAttempts=0;
        String local,remote,actor="Application non identifiée",attribution="UID non disponible",journalGroup="android";
        boolean systemApp=false,updatedSystemApp=false;
        JSONArray packages=new JSONArray(),security=new JSONArray();
        String tlsName="",tlsStatus="non_observe";boolean ech=false;
    }
    @Override public void onCreate(){
        super.onCreate();main=new Handler(getMainLooper());store=EventStore.get(this);
        connectivity=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("reseau","Connexions des applications",NotificationManager.IMPORTANCE_LOW));
        starting=true;stateText="Démarrage";
        Notification n=notification("Préparation du suivi des connexions");
        if(Build.VERSION.SDK_INT>=34)startForeground(3,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(3,n);
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,30,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,31,new Intent(this,NetworkCaptureService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"reseau").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Journal local · connexions").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Arrêter les connexions",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null && STOP.equals(intent.getAction())){Continuous.prefs(this).edit().putBoolean("vpn_enabled",false).apply();stopped=true;stopSelf();return START_NOT_STICKY;}
        if(!Continuous.enabled(this)||!Continuous.prefs(this).getBoolean("vpn_enabled",true)){stopSelf();return START_NOT_STICKY;}
        if(engine==null){
            callback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){checkNetwork();}
                @Override public void onLost(Network n){checkNetwork();}
                @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){checkNetwork();}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){checkNetwork();}
            };
            try{connectivity.registerDefaultNetworkCallback(callback,main);}catch(Exception e){lastError="Suivi des changements réseau indisponible : "+e.getClass().getSimpleName();}
            engine=new Thread(()->collect(),"journal-reseau");engine.start();
        }
        return START_STICKY;
    }
    private Config config(){
        Network chosen=connectivity.getActiveNetwork();
        NetworkCapabilities caps=chosen==null?null:connectivity.getNetworkCapabilities(chosen);
        if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
            chosen=null;
            for(Network network:connectivity.getAllNetworks()){
                NetworkCapabilities c=connectivity.getNetworkCapabilities(network);
                if(c!=null&&!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){
                    chosen=network;caps=c;
                    if(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))break;
                }
            }
        }
        if(chosen==null||caps==null)return null;
        LinkProperties properties=connectivity.getLinkProperties(chosen);
        if(properties==null||properties.getDnsServers().isEmpty())return null;
        Config out=new Config();out.network=chosen;out.dns=new ArrayList<>(properties.getDnsServers());
        out.metered=!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        out.transport=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"Cellulaire":caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)?"Bluetooth":"Autre";
        out.key=chosen.toString()+"/"+out.dns.toString()+"/"+out.metered;
        return out;
    }
    private void checkNetwork(){
        if(activeConfig.isEmpty())return;
        try{Config now=config();if(now==null||!now.key.equals(activeConfig))reconfigure=true;}
        catch(Exception e){reconfigure=true;}
    }
    private void collect(){
        boolean once=false;long waitingAt=0;
        try{
            System.loadLibrary("journalrelay");
            while(!stopped){
                reconfigure=false;current=config();
                if(current==null){stateText="En attente d’un réseau avec DNS";if(waitingAt==0){waitingAt=SystemClock.elapsedRealtime();record("collecteur","Journal local","Capture en attente de réseau","Relais local",EventStore.object("coverage_gap",true,"reason","Aucun réseau physique avec configuration DNS disponible; ne prouve pas une panne de l’opérateur"));}Thread.sleep(250);continue;}
                if(waitingAt>0){record("collecteur","Journal local","Réseau disponible pour la capture","Relais local",EventStore.object("waiting_interval_ms",SystemClock.elapsedRealtime()-waitingAt,"coverage_gap",true));waitingAt=0;}
                if(VpnService.prepare(this)!=null)throw new IOException("Autorisation VPN requise");
                activeConfig=current.key;
                Builder builder=new Builder().setSession("Journal local · connexions").setMtu(1500)
                    .addAddress("10.203.0.1",32).addAddress("fd75:6a6f:7572::1",128)
                    .addRoute("0.0.0.0",0).addRoute("::",0).setBlocking(false)
                    .addDisallowedApplication(getPackageName()).setUnderlyingNetworks(new Network[]{current.network});
                if(Build.VERSION.SDK_INT>=29)builder.setMetered(current.metered);
                for(InetAddress dns:current.dns)builder.addDnsServer(dns);
                try(ParcelFileDescriptor tunnel=builder.establish()){
                    if(tunnel==null)throw new IOException("Interface VPN non créée");
                    session=UUID.randomUUID().toString();running=true;starting=false;stateText="Connexions actives · "+current.transport;
                    record("collecteur","Journal local",once?"Capture réseau reprise":"Capture réseau démarrée","VPN local",EventStore.object("session",session,"no_remote_gateway",true,"tls_decryption",false,"excluded_app",getPackageName(),"dns_source","Réseau physique Android","scope","Paquets IP routés vers cette interface; autres profils et appareils partagés non garantis"));
                    once=true;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(3,notification(stateText));
                    int result=runNative(tunnel.getFd());
                    if(result!=0&&!stopped)throw new IOException("Relais interrompu, code "+result);
                }finally{running=false;activeConfig="";flows.clear();}
                if(!stopped){starting=true;stateText="Reconnexion";record("collecteur","Journal local","Changement du réseau de capture","Interruption de la capture",EventStore.object("coverage_gap",true,"reason","Reconfiguration du réseau physique ou DNS"));}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Throwable e){lastError="Capture interrompue : "+e.getClass().getSimpleName()+" · "+String.valueOf(e.getMessage());record("collecteur","Journal local","Erreur de capture réseau","Relais local",EventStore.object("error",lastError,"coverage_gap",true));}
        finally{
            running=false;starting=false;activeConfig="";stateText=lastError.isEmpty()?"Arrêté":"Arrêté avec erreur";
            record("collecteur","Journal local","Capture réseau arrêtée","VPN local",EventStore.object("coverage_gap",true,"no_boot_restart",true));
            main.post(()->stopSelf());
        }
    }
    private void record(String category,String actor,String action,String destination,JSONObject details){
        String transport=("trafic".equals(category)||"dns".equals(category))&&current!=null?current.transport:"Interne";
        if(!store.add(category,actor,action,destination,transport,"VPN local · Journal local",details)){lastError=EventStore.lastError;stopped=true;}
    }
    // Called on the native worker thread. Socket duplicate must not close the original.
    public boolean protectNativeSocket(int fd){
        if(!protect(fd))return false;
        try(ParcelFileDescriptor duplicate=ParcelFileDescriptor.fromFd(fd)){
            current.network.bindSocket(duplicate.getFileDescriptor());return true;
        }catch(IOException e){lastError="Socket non reliée au réseau physique : "+e.getClass().getSimpleName();return false;}
    }
    public boolean shouldStopNative(){return stopped||reconfigure;}
    private void identify(Flow f){
        if(f.uid>=0||f.lookupAttempts>=3)return;f.lookupAttempts++;
        if(Build.VERSION.SDK_INT<29||(f.protocol!=6&&f.protocol!=17)){f.attribution="Identification Android indisponible pour ce protocole ou cette version";return;}
        try{
            f.uid=connectivity.getConnectionOwnerUid(f.protocol,new InetSocketAddress(InetAddress.getByName(f.local),f.localPort),new InetSocketAddress(InetAddress.getByName(f.remote),f.remotePort));
            if(f.uid<0){f.attribution="Android n’a pas identifié le propriétaire de cette connexion";return;}
            String[] packages=getPackageManager().getPackagesForUid(f.uid);
            if(packages==null||packages.length==0){f.actor="Android · UID "+f.uid;f.journalGroup="android";f.attribution="UID Android observé; nom du paquet non accessible";return;}
            for(String name:packages)f.packages.put(name);
            f.security=SecurityContext.forPackages(this,f.packages);
            // Android reserves appId values below 10000 for framework/system
            // services. Keep these events visible as Android even when the
            // package list contains candidates; candidates are not authorship.
            boolean reservedUid=f.uid%100000<10000;
            if(packages.length>1||reservedUid){f.actor="Android · UID "+f.uid;f.journalGroup="android";f.attribution=packages.length>1?"Plusieurs paquets partagent cet UID; application précise inconnue":"UID système réservé; paquet candidat non traité comme auteur certain";return;}
            f.actor=packages[0];
            try{ApplicationInfo app=getPackageManager().getApplicationInfo(packages[0],0);f.systemApp=(app.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;f.updatedSystemApp=(app.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0;f.journalGroup=f.systemApp?"system":"user";f.actor=getPackageManager().getApplicationLabel(app).toString();}catch(PackageManager.NameNotFoundException ignored){f.journalGroup="user";}
            f.attribution="Propriétaire du flux identifié par l’API Android du VPN actif";
        }catch(Exception e){f.uid=-1;f.attribution="Identification non disponible : "+e.getClass().getSimpleName();}
    }
    private JSONObject details(long id,Flow f){
        return EventStore.object("flow_id",session+":"+id,"flow_correlation_id",f.correlationId,"native_flow_id",id,
            "correlation_scope","Identifiant local aléatoire 128 bits; il reste dans Journal local et n’est pas ajouté aux paquets Internet",
            "first_observed_ms",f.firstMs,"first_outbound_ms",f.firstOutboundMs==0?JSONObject.NULL:f.firstOutboundMs,"first_inbound_ms",f.firstInboundMs==0?JSONObject.NULL:f.firstInboundMs,
            "outbound_observed",f.firstOutboundMs!=0,"inbound_observed",f.firstInboundMs!=0,
            "flow_linkage","Les deux directions partagent le même état de connexion du relais local; cela relie le retour réseau au flux sans déchiffrer TLS et sans résoudre un UID partagé en paquet individuel",
            "ip_version",f.version,
            "protocol",f.protocol==6?"TCP":f.protocol==17?"UDP":f.protocol==1?"ICMP":f.protocol==58?"ICMPv6":String.valueOf(f.protocol),
            "local_ip",f.local,"local_port",f.localPort,"remote_ip",f.remote,"port",f.remotePort,"uid",f.uid,"packages",f.packages,"package_list_scope","Paquets retournés par Android; visibilité éventuellement limitée",
            "attribution",f.attribution,"journal_group",f.journalGroup,"system_app",f.systemApp,"updated_system_app",f.updatedSystemApp,"package_list_scope","Paquets retournés par Android; visibilité éventuellement limitée",
            "cross_analysis",EventStore.object("status",f.uid<0?"uid_inconnu":(f.packages.length()>1||f.uid==1000)?"uid_partage_non_resolu":"uid_observe","pid",JSONObject.NULL,"process_name",JSONObject.NULL,"service",JSONObject.NULL,
                "automatic_system_logcat","Non accessible à cette application ordinaire","diagnostic_correlation","Disponible à la demande après import d’un diagnostic horodaté; une coïncidence temporelle ne prouve pas la propriété d’un socket"),
            "security_context",f.security,"tls_sni",f.tlsName,"tls_observation",f.protocol==6?f.tlsStatus:"Non analysé (UDP/QUIC et autres protocoles)","ech_extension_present",f.ech,"sni_scope",f.ech?"Nom externe possible; ECH ou GREASE, nom interne non observable":"Nom annoncé dans le ClientHello; service ou contenu non prouvé","transport",current==null?"Inconnu":current.transport,
            "scope","Métadonnées du flux IP; aucun contenu de message conservé, aucune frontière de message déduite");
    }
    private String destination(Flow f){return(f.version==6?"["+f.remote+"]":f.remote)+":"+f.remotePort;}
    public void onFlowOpen(long id,int version,int protocol,String local,int localPort,String remote,int remotePort){
        Flow f=new Flow();f.version=version;f.protocol=protocol;f.local=local;f.localPort=localPort;f.remote=remote;f.remotePort=remotePort;
        identify(f);flows.put(id,f);record("trafic",f.actor,"Flux réseau observé",destination(f),details(id,f));
    }
    public void onFlowDirection(long id,boolean outgoing,long packetBytes,long observedMs){
        Flow f=flows.get(id);if(f==null)return;identify(f);
        if(outgoing){if(f.firstOutboundMs!=0)return;f.firstOutboundMs=observedMs;}
        else{if(f.firstInboundMs!=0)return;f.firstInboundMs=observedMs;}
        JSONObject d=details(id,f);
        try{d.put("direction",outgoing?"sortant":"entrant").put("first_packet_bytes",packetBytes)
            .put("same_native_flow",true).put("network_return_on_same_flow",!outgoing);}
        catch(JSONException e){throw new IllegalStateException(e);}
        record("trafic",f.actor,outgoing?"Premier paquet sortant du flux":"Premier paquet entrant relié au même flux",destination(f),d);
    }
    public void onFlowUpdate(long id,long tx,long rx,long txPackets,long rxPackets,int status,int error,boolean closed,long lastMs){
        Flow f=flows.get(id);if(f==null)return;identify(f);
        JSONObject d=details(id,f);
        try{d.put("tx_bytes",tx).put("rx_bytes",rx).put("tx_packets",txPackets).put("rx_packets",rxPackets).put("bytes",tx+rx)
            .put("counter_mode","Cumul du flux; ne pas additionner les instantanés").put("direction","bidirectionnel")
            .put("result",status>=0&&status<STATES.length?STATES[status]:String.valueOf(status)).put("error_code",error).put("closed",closed).put("last_packet_ms",lastMs);
        }catch(JSONException e){throw new IllegalStateException(e);}
        record("trafic",f.actor,(closed?"Flux fermé":"Trafic observé")+" · ↑ "+tx+" o / ↓ "+rx+" o",destination(f),d);
        if(closed)flows.remove(id);
    }
    public void onTlsHello(long id,String name,int status,boolean ech){
        Flow f=flows.get(id);if(f==null)return;identify(f);f.tlsName=name;f.ech=ech;
        f.tlsStatus=status==1?(name.isEmpty()?"ClientHello sans nom SNI":"ClientHello observé"):status==-1?"Début de flux non TLS":status==-2?"ClientHello non décodable":status==-3?"Limite de 32 Kio atteinte":status==-4?"Retransmission contradictoire":status==-6?"Mémoire d’observation indisponible":"ClientHello incomplet ou non reçu";
        if(status==1)record("trafic",f.actor,ech?"Nom TLS externe possible (ECH/GREASE)":name.isEmpty()?"TLS sans nom visible":"Nom TLS observé",destination(f),details(id,f));
    }
    public void onDnsQuestion(long id,String name,int type){
        Flow f=flows.get(id);if(f==null)return;JSONObject d=details(id,f);
        try{d.put("question",name).put("query_type",type).put("scope","Question DNS UDP en clair; ne prouve ni le contenu échangé ni le domaine des autres flux");}catch(JSONException e){throw new IllegalStateException(e);}
        record("dns",f.actor,"Question DNS observée",name,d);
    }
    public void onNativeProblem(String label,long count){record("collecteur","Journal local",label,"Relais local",EventStore.object("count",count,"coverage_gap",true));}
    @Override public void onRevoke(){Continuous.prefs(this).edit().putBoolean("vpn_enabled",false).apply();stopped=true;main.post(()->stopSelf());}
    @Override public void onDestroy(){
        stopped=true;if(callback!=null)try{connectivity.unregisterNetworkCallback(callback);}catch(Exception ignored){}
        if(engine!=null)engine.interrupt();else{starting=false;running=false;stateText="Arrêté";}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}