package fr.erick.journallocal;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.net.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.util.HashMap;

public final class RecorderService extends Service {
    public static final String STOP="fr.erick.journallocal.STOP";
    public static final String REFRESH="fr.erick.journallocal.REFRESH";
    public static volatile boolean running=false;
    public static volatile boolean networkRegistered=false;
    public static volatile boolean bluetoothRegistered=false;
    public static volatile long lastAlive=0;
    private HandlerThread thread;
    private Handler worker;
    private EventStore store;
    private ConnectivityManager connectivity;
    private SharedPreferences prefs;
    private BroadcastReceiver systemReceiver, bluetoothReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final HashMap<String,String> networkKinds=new HashMap<>();
    private boolean started=false,explicitStop=false;
    private volatile boolean stopping=false;
    private long previousElapsed=0,previousWall=0;
    private NetworkHealth health;private ConnectivityManager.NetworkCallback physicalCallback;
    private final Runnable healthSample=()->{if(!stopping&&health!=null)health.sample();};
    private void sampleSoon(){worker.removeCallbacks(healthSample);worker.postDelayed(healthSample,150);}
    private final Runnable heartbeat=new Runnable(){@Override public void run(){
        if(stopping)return;
        long elapsed=SystemClock.elapsedRealtime();
        if(previousElapsed>0 && elapsed-previousElapsed>150000)
            record("collecteur","Journal local","Intervalle sans signal de vie","Continuité de collecte","Interne","Horloge monotone Android",EventStore.object("interval_ms",elapsed-previousElapsed,"interpretation","Veille ou suspension possible; événements manquants non quantifiables"));
        lastAlive=System.currentTimeMillis();
        if(previousWall>0&&Math.abs((lastAlive-previousWall)-(elapsed-previousElapsed))>5000)record("collecteur","Journal local","Écart entre horloges détecté","Corrélation temporelle","Interne","Horloges Android",EventStore.object("wall_delta_ms",lastAlive-previousWall,"elapsed_delta_ms",elapsed-previousElapsed,"interpretation","Comparer les temps monotones; les heures civiles ont changé"));
        previousElapsed=elapsed;previousWall=lastAlive;prefs.edit().putLong("last_alive",lastAlive).apply();
        record("collecteur","Journal local","Signal de vie","Collecteur","Interne","Service local",EventStore.object("elapsed_ms",elapsed,"network_health",health==null?JSONObject.NULL:health.sample(),"collector_pid",android.os.Process.myPid()));
        long rx=TrafficStats.getTotalRxBytes(),tx=TrafficStats.getTotalTxBytes();
        if(rx>=0 && tx>=0)record("reseau","Journal local","Lecture des compteurs réseau Android","Compteurs depuis le démarrage","Global","TrafficStats",EventStore.object("rx_bytes",rx,"tx_bytes",tx,"attribution","Lecture périodique par le collecteur, tout l’appareil; ni applications ni destinations identifiées"));
        JournalSegments.request(RecorderService.this);AnomalyMonitor.request(RecorderService.this);TrackerIndex.get(RecorderService.this).request();ApkEvidence.get(RecorderService.this).request();
        long lastInventory=prefs.getLong("last_inventory_request",0);
        if(System.currentTimeMillis()-lastInventory>900000){prefs.edit().putLong("last_inventory_request",System.currentTimeMillis()).apply();PermissionAudit.get(RecorderService.this).scan();}
        worker.postDelayed(this,60000);
    }};
    @Override public void onCreate(){
        super.onCreate();store=EventStore.get(this);prefs=getSharedPreferences("journal",MODE_PRIVATE);DefenseMonitor.start(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("collecte","Collecte du journal",NotificationManager.IMPORTANCE_LOW));
        Notification notification=notification("Collecte locale en cours",true);
        if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,notification);
        thread=new HandlerThread("journal-events");thread.start();worker=new Handler(thread.getLooper());
    }
    private Notification notification(String text,boolean ongoing){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RecorderService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"collecte").setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("Journal local").setContentText(text).setContentIntent(content).setOngoing(ongoing).addAction(android.R.drawable.ic_media_pause,"Arrêter",stop).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(STOP.equals(action)){Continuous.stop(this);explicitStop=true;prefs.edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}
        if(!Continuous.enabled(this)){stopSelf();return START_NOT_STICKY;}
        if(!started){started=true;running=true;worker.post(()->startRecording());}
        else if(REFRESH.equals(action))worker.post(()->refreshBluetooth());
        return START_STICKY;
    }
    private void record(String category,String actor,String action,String destination,String transport,String source,JSONObject details){
        if(!store.add(category,actor,action,destination,transport,source,details)){
            getSystemService(NotificationManager.class).notify(2,notification("Collecte interrompue : stockage indisponible",false));stopSelf();
        }
    }
    private void startRecording(){
        if(stopping)return;
        long oldAlive=prefs.getLong("last_alive",0);
        if(prefs.getBoolean("enabled",false)&&oldAlive>0)record("collecteur","Journal local","Reprise après interruption","Continuité de collecte","Interne","État persistant du collecteur",EventStore.object("last_alive_ms",oldAlive,"until_ms",System.currentTimeMillis(),"missing_events","Inconnus"));
        prefs.edit().putBoolean("enabled",true).apply();
        record("collecteur","Journal local","Collecte démarrée","Collecteur","Interne","Service local",EventStore.object("android",Build.VERSION.RELEASE,"api",Build.VERSION.SDK_INT,"model",Build.MODEL,"network_payload_capture",false,"security_patch",Build.VERSION.SECURITY_PATCH,"collector_pid",android.os.Process.myPid(),"system_logcat_access","Non accessible automatiquement; diagnostic externe facultatif"));
        PowerManager power=getSystemService(PowerManager.class);
        record("systeme","Android","État initial de l’écran","Écran","Interne","PowerManager",EventStore.object("interactive",power.isInteractive()));
        systemReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){handleSystem(intent,isInitialStickyBroadcast());}};
        IntentFilter filter=new IntentFilter();
        for(String a:new String[]{Intent.ACTION_BATTERY_CHANGED,Intent.ACTION_POWER_CONNECTED,Intent.ACTION_POWER_DISCONNECTED,Intent.ACTION_SCREEN_ON,Intent.ACTION_SCREEN_OFF,Intent.ACTION_USER_PRESENT,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_DEVICE_STORAGE_LOW,Intent.ACTION_DEVICE_STORAGE_OK,UsbManager.ACTION_USB_DEVICE_ATTACHED,UsbManager.ACTION_USB_DEVICE_DETACHED})filter.addAction(a);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(systemReceiver,filter,null,worker,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(systemReceiver,filter,null,worker);
        registerNetwork();refreshBluetooth();worker.post(heartbeat);
    }
    private void handleSystem(Intent intent,boolean initial){
        String action=intent.getAction();if(action==null||stopping)return;
        JSONObject details=EventStore.object("android_action",action);
        String label=action,resource="Système",transport="Interne";
        if(Intent.ACTION_BATTERY_CHANGED.equals(action)){
            label=initial?"État initial de la batterie":"État de batterie reçu";resource="Batterie";
            details=EventStore.object("level",intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),"scale",intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1),"status",intent.getIntExtra(BatteryManager.EXTRA_STATUS,-1),"plugged",intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1),"temperature_tenths_c",intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1),"voltage_mv",intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1),"initial_snapshot",initial);
        }else if(Intent.ACTION_POWER_CONNECTED.equals(action)){label="Alimentation branchée";resource="Alimentation";}
        else if(Intent.ACTION_POWER_DISCONNECTED.equals(action)){label="Alimentation débranchée";resource="Alimentation";}
        else if(Intent.ACTION_SCREEN_ON.equals(action)){label="Écran allumé";resource="Écran";}
        else if(Intent.ACTION_SCREEN_OFF.equals(action)){label="Écran éteint";resource="Écran";}
        else if(Intent.ACTION_USER_PRESENT.equals(action)){label="Utilisateur présent après déverrouillage";resource="Verrouillage";}
        else if(Intent.ACTION_TIME_CHANGED.equals(action)){label="Heure système modifiée";resource="Horloge";}
        else if(Intent.ACTION_TIMEZONE_CHANGED.equals(action)){label="Fuseau horaire modifié";resource="Horloge";}
        else if(Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)){label="Stockage faible";resource="Stockage";}
        else if(Intent.ACTION_DEVICE_STORAGE_OK.equals(action)){label="Stockage disponible";resource="Stockage";}
        else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)||UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
            label=UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)?"Périphérique USB hôte attaché":"Périphérique USB hôte détaché";resource="USB hôte";transport="USB";
            UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(device!=null)details=EventStore.object("vendor_id",device.getVendorId(),"product_id",device.getProductId(),"device_class",device.getDeviceClass(),"data_access",false);
        }
        record("systeme","Android",label,resource,transport,"Broadcast système Android",details);
    }
    private static String kind(NetworkCapabilities caps){
        if(caps==null)return "Inconnu";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "VPN";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "Wi-Fi";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "Cellulaire";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))return "Bluetooth";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet";
        if(Build.VERSION.SDK_INT>=31&&caps.hasTransport(NetworkCapabilities.TRANSPORT_USB))return "USB";
        return "Autre";
    }
    private void registerNetwork(){
        connectivity=getSystemService(ConnectivityManager.class);health=new NetworkHealth(this);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){sampleSoon();record("reseau","Android","Réseau par défaut disponible","Réseau "+network,"Inconnu","ConnectivityManager",EventStore.object("network_id",network.toString()));}
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
                sampleSoon();String transport=kind(caps);networkKinds.put(network.toString(),transport);
                record("reseau","Android","État du réseau par défaut reçu","Réseau "+network,transport,"ConnectivityManager.onCapabilitiesChanged",EventStore.object("internet_capability",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),"validated",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),"unmetered",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),"cellular_generation","Non déterminée","per_app_connections",false));
            }
            @Override public void onLinkPropertiesChanged(Network network,LinkProperties properties){
                JSONArray dns=new JSONArray();for(InetAddress address:properties.getDnsServers())dns.put(address.getHostAddress());
                record("reseau","Android","Configuration réseau reçue","Réseau "+network,networkKinds.containsKey(network.toString())?networkKinds.get(network.toString()):"Inconnu","ConnectivityManager.onLinkPropertiesChanged",EventStore.object("interface",properties.getInterfaceName(),"dns_resolvers",dns,"dns_queries_captured",false));
            }
            @Override public void onLost(Network network){sampleSoon();String type=networkKinds.remove(network.toString());record("reseau","Android","Réseau par défaut perdu","Réseau "+network,type==null?"Inconnu":type,"ConnectivityManager.onLost",EventStore.object("network_id",network.toString()));}
        };
        physicalCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){sampleSoon();}
            @Override public void onLost(Network n){sampleSoon();}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){sampleSoon();}
        };
        try{connectivity.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),physicalCallback,worker);}
        catch(Exception e){record("collecteur","Journal local","Suivi des réseaux physiques limité","Santé des réseaux","Interne","Enregistrement API",EventStore.object("error",e.getClass().getSimpleName(),"fallback","Relevé au signal de vie"));}
        try{connectivity.registerDefaultNetworkCallback(networkCallback,worker);networkRegistered=true;}
        catch(Exception e){networkRegistered=false;record("collecteur","Journal local","Source réseau indisponible","Configuration du collecteur","Interne","Enregistrement API",EventStore.object("error",e.getClass().getSimpleName()));}
    }
    private void refreshBluetooth(){
        if(bluetoothReceiver!=null){try{unregisterReceiver(bluetoothReceiver);}catch(IllegalArgumentException ignored){}bluetoothReceiver=null;}bluetoothRegistered=false;
        boolean enabled=prefs.getBoolean("bluetooth",false);
        boolean permission=Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        if(!enabled||!permission)return;
        bluetoothReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
            if(stopping)return;String action=intent.getAction();String label;
            if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action))label="Lien Bluetooth connecté";
            else if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action))label="Lien Bluetooth déconnecté";
            else if(BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action))label="État d’appairage Bluetooth modifié";
            else if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(action))label="État de l’adaptateur Bluetooth modifié";else return;
            BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);String address="Non fourni",name="Non fourni";
            try{if(device!=null){address=device.getAddress();if(device.getName()!=null)name=device.getName();}}catch(SecurityException e){name="Permission révoquée";}
            record("bluetooth","Android",label,address,"Bluetooth","Broadcast Bluetooth Android",EventStore.object("name",name,"state",intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1),"bond_state",intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,-1),"android_action",action,"payload_capture",false));
        }};
        IntentFilter filter=new IntentFilter();for(String a:new String[]{BluetoothDevice.ACTION_ACL_CONNECTED,BluetoothDevice.ACTION_ACL_DISCONNECTED,BluetoothDevice.ACTION_BOND_STATE_CHANGED,BluetoothAdapter.ACTION_STATE_CHANGED})filter.addAction(a);
        try{
            // These protected system broadcasts can originate from the separate Bluetooth UID.
            if(Build.VERSION.SDK_INT>=33)registerReceiver(bluetoothReceiver,filter,null,worker,Context.RECEIVER_EXPORTED);else registerReceiver(bluetoothReceiver,filter,null,worker);
            bluetoothRegistered=true;record("collecteur","Journal local","Source Bluetooth activée","Collecteur Bluetooth","Bluetooth","Service local",EventStore.object("scanning",false,"payload_capture",false));
        }catch(Exception e){bluetoothRegistered=false;bluetoothReceiver=null;record("collecteur","Journal local","Source Bluetooth indisponible","Collecteur Bluetooth","Bluetooth","Enregistrement API",EventStore.object("error",e.getClass().getSimpleName()));}
    }
    @Override public void onDestroy(){
        DefenseMonitor.stop(this);
        running=false;stopping=true;networkRegistered=false;bluetoothRegistered=false;
        if(worker!=null){worker.removeCallbacksAndMessages(null);worker.post(()->{
            networkRegistered=false;bluetoothRegistered=false;
            if(systemReceiver!=null)try{unregisterReceiver(systemReceiver);}catch(IllegalArgumentException ignored){}
            if(bluetoothReceiver!=null)try{unregisterReceiver(bluetoothReceiver);}catch(IllegalArgumentException ignored){}
            if(connectivity!=null&&physicalCallback!=null)try{connectivity.unregisterNetworkCallback(physicalCallback);}catch(IllegalArgumentException ignored){}
            if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(IllegalArgumentException ignored){}
            store.add("collecteur","Journal local",explicitStop?"Collecte arrêtée à la demande":"Service interrompu","Collecteur","Interne","Cycle de vie du service",EventStore.object("explicit_stop",explicitStop));
            if(explicitStop)prefs.edit().putBoolean("enabled",false).apply();thread.quitSafely();
        });}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
