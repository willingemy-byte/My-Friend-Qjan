package fr.erick.journallocal;

import java.io.Serializable;
import java.net.IDN;
import java.util.*;

/** Deterministic metadata rules. No network, Android, or content decryption dependency. */
public final class AnomalyRules implements Serializable {
    private static final long serialVersionUID = 3L;
    public static final long WINDOW = 300000L, GROUP = 900000L;
    public static final class Settings implements Serializable {
        private static final long serialVersionUID = 1L;
        public boolean failures=true, volume=true, dns=true, collection=true, watch=true, research=true;
        public int failureCount=8, uploadMiB=10;
        public String[] domains=new String[0];
        public void validate() {
            if(failureCount<2||failureCount>100||uploadMiB<1||uploadMiB>1024||domains.length>50)
                throw new IllegalArgumentException("Échecs : 2 à 100; volume : 1 à 1024 Mio; 50 domaines maximum.");
            LinkedHashSet<String> normalized=new LinkedHashSet<>();
            for(String s:domains){String d=domain(s);if(d.isEmpty())throw new IllegalArgumentException("Domaine invalide : utiliser un nom seul, sans URL ni joker.");normalized.add(d);}
            domains=normalized.toArray(new String[0]);
        }
    }
    public static final class Event {
        public long id, wall, elapsed=-1, tx=-1;
        public int uid=-1, error, port;
        public String category="", actor="", action="", destination="", source="", flow="", result="", problem="", query="";
        public String[] packages=new String[0], resolvers=null;
        public boolean closed, coverageGap;
        public long interval;
    }
    public static final class Finding {
        public String key, rule, type, severity, title, actor, subject, explanation, advice;
        public long eventId, wall;
        public Map<String,String> facts=new LinkedHashMap<>();
        public List<Long> evidence=new ArrayList<>();
    }
    public interface Sink { void emit(Finding finding); }
    private static final class Flow implements Serializable {
        private static final long serialVersionUID=1L;
        long tx, elapsed, id; boolean closed, portTrace;
    }
    private static final class Bucket implements Serializable {
        private static final long serialVersionUID=1L;
        long second, value, first, last;
    }
    private static final class Window implements Serializable {
        private static final long serialVersionUID=1L;
        final ArrayDeque<Bucket> buckets=new ArrayDeque<>();
        long touched;
        void add(long at,long value,long id){
            touched=at;long second=at/1000;
            // Timestamp of observation, not a claim about the exact packet transmission time.
            while(!buckets.isEmpty()&&buckets.peekFirst().second<=second-300)buckets.removeFirst();
            if(buckets.isEmpty()||buckets.peekLast().second!=second){Bucket b=new Bucket();b.second=second;b.first=id;buckets.addLast(b);}
            Bucket b=buckets.peekLast();b.value+=value;b.last=id;
        }
        long total(){long n=0;for(Bucket b:buckets)n+=b.value;return n;}
        List<Long> evidence(){List<Long> ids=new ArrayList<>();if(!buckets.isEmpty()){ids.add(buckets.peekFirst().first);ids.add(buckets.peekLast().last);}return ids;}
    }
    private static final class Dns implements Serializable {
        private static final long serialVersionUID=1L;
        String values; long id;
    }
    public long checkpoint, processed, firstWall, lastWall, unknownAttribution, uncertainCounters;
    private long lastElapsed=-1, epoch, time, pruneAt;
    private final Map<String,Flow> flows=new HashMap<>();
    private final Map<String,Window> uploads=new HashMap<>(), failures=new HashMap<>();
    private final Map<String,Dns> networks=new HashMap<>();
    public void resetWindows(){uploads.clear();failures.clear();}
    public void accept(Event e,Settings settings,Sink sink){
        if(e.id<=checkpoint)return;
        processed++;checkpoint=e.id;lastWall=e.wall;if(firstWall==0)firstWall=e.wall;
        if(e.category.equals("collecteur")&&e.action.equals("Collecte démarrée")){
            // Older logs have no boot identifier. Do not compare across collection sessions,
            // even when the first uptime of a new boot exceeds the previous saved uptime.
            epoch++;flows.clear();networks.clear();resetWindows();lastElapsed=-1;pruneAt=0;
        }
        if(e.elapsed>=0){
            if(lastElapsed>=0&&e.elapsed<lastElapsed-10000){epoch++;flows.clear();networks.clear();resetWindows();pruneAt=0;}
            time=Math.max(e.elapsed, e.elapsed<lastElapsed-10000?e.elapsed:lastElapsed);lastElapsed=time;
        }
        boolean clock=e.elapsed>=0;
        String appKey=e.uid<0?"":e.uid+":"+String.join(",",e.packages);
        if(e.packages.length!=1||e.uid==1000)appKey=""; // A shared UID does not identify an individual application.
        if(("trafic".equals(e.category)||"dns".equals(e.category))&&appKey.isEmpty())unknownAttribution++;
        if(clock&&"trafic".equals(e.category)&&!e.flow.isEmpty())traffic(e,appKey,settings,sink);
        if(clock&&"ConnectivityManager.onLinkPropertiesChanged".equals(e.source)&&e.resolvers!=null){
            String key=epoch+":"+e.destination, values=canonical(e.resolvers);
            Dns before=networks.get(key);
            if(settings.dns&&before!=null&&!before.values.isEmpty()&&!values.isEmpty()&&!before.values.equals(values)){
                Finding f=finding(e,"dns","anomaly","information","Résolveurs DNS modifiés",e.destination,
                    "Android a communiqué deux listes différentes pour le même réseau. Le premier relevé et l’ordre des adresses ne déclenchent pas cette règle.",
                    "Comparer avec un changement de connexion ou de DNS privé. Ce constat ne prouve pas une modification malveillante.");
                f.facts.put("Avant",before.values);f.facts.put("Après",values);f.evidence.add(before.id);sink.emit(f);
            }
            Dns next=new Dns();next.values=values;next.id=e.id;networks.put(key,next);
        }
        if("dns".equals(e.category)||("trafic".equals(e.category)&&"Nom TLS observé".equals(e.action))){
            String q=domain(e.query);if(!q.isEmpty()){
                if(settings.watch)for(String watched:settings.domains)if(within(q,watched)){
                    Finding f=finding(e,"watch","anomaly","information","Domaine de ta liste observé",appKey+":"+q,
                        "Un nom DNS en clair ou SNI visible correspond à un domaine que tu as choisi de surveiller.",
                        "Vérifier l’application et l’heure. Ce nom ne prouve pas une connexion réussie ni le contenu transmis.");
                    f.facts.put("Nom observé",q);f.facts.put("Règle choisie",watched);sink.emit(f);break;
                }
                if(settings.research){String match=researchMatch(q);if(!match.isEmpty()){
                    Finding f=finding(e,"research","trace","information","Correspondance avec le rapport",appKey+":"+q,
                        "Le nom DNS ou SNI observé correspond à une famille de domaines mentionnée dans le rapport fourni. Le rapport sert de liste de recherche, pas de preuve sur cette connexion.",
                        "Examiner le nom complet et l’acteur. Cela ne démontre ni OTEL, ni un envoi de prompts, ni le routage FedRAMP du compte.");
                    f.facts.put("Nom observé",q);f.facts.put("Correspondance",match);sink.emit(f);
                }}
            }
        }
        if(settings.collection&&"collecteur".equals(e.category)){
            boolean gap=e.interval>150000;
            boolean problem=!e.problem.isEmpty()||e.action.contains("non relayés")||e.action.contains("non décodés")||e.action.equals("Erreurs de relais")||e.action.startsWith("Limite de sockets");
            if(gap||problem){
                Finding f=finding(e,"collection","anomaly",problem?"attention":"information",problem?"Collecte à vérifier":"Intervalle de collecte à vérifier",e.action,
                    "Le collecteur a enregistré : "+e.action+". Les interactions manquantes ne peuvent pas être reconstituées par cette règle.",
                    "Consulter Sources. Une veille, une limite du relais ou un problème de connexion peut expliquer ce signalement.");
                if(gap)f.facts.put("Intervalle",String.valueOf(e.interval/1000)+" secondes");
                if(!e.problem.isEmpty())f.facts.put("Diagnostic",e.problem);sink.emit(f);
            }
        }
        if(clock&&time-pruneAt>60000){pruneAt=time;prune();}
    }
    private void traffic(Event e,String appKey,Settings s,Sink sink){
        Flow previous=flows.get(e.flow);boolean open=e.tx<0;
        boolean portTrace=previous!=null&&previous.portTrace;
        if(s.research&&!portTrace&&(e.port==4317||e.port==4318)){
            Finding f=finding(e,"research-port","trace","information","Port compatible avec OTLP observé",appKey+":"+e.destination,
                "Le port distant est "+e.port+", un port par défaut d’OTLP. Seul le numéro du port est observé; le protocole et le contenu ne sont pas identifiés.",
                "Un autre service peut utiliser ce port. OTLP peut aussi passer par d’autres ports, dont 443. Ce signal ne confirme aucun export de prompts.");
            f.facts.put("Destination contactée",e.destination);f.facts.put("Indice", "Port uniquement · OTLP non confirmé");sink.emit(f);portTrace=true;
        }
        if(open){if(previous==null){Flow f=new Flow();f.elapsed=time;f.id=e.id;f.portTrace=portTrace;flows.put(e.flow,f);}else previous.portTrace=portTrace;return;}
        long delta=0;
        if(previous!=null&&!previous.closed&&e.tx>=previous.tx&&time>=previous.elapsed&&time-previous.elapsed<=WINDOW)delta=e.tx-previous.tx;
        else if(previous==null||(!previous.closed&&e.tx>previous.tx))uncertainCounters++;
        if(!appKey.isEmpty()&&s.volume&&delta>0){
            Window w=uploads.get(appKey);if(w==null){w=new Window();uploads.put(appKey,w);}w.add(time,delta,e.id);
            if(w.total()>=(long)s.uploadMiB*1048576){
                Finding f=finding(e,"volume","anomaly","attention","Seuil de trafic envoyé dépassé",appKey,
                    "Les augmentations des compteurs de cette application dépassent le seuil réglé sur les cinq dernières minutes d’observation. Les instantanés cumulatifs ne sont pas additionnés.",
                    "Comparer avec un envoi de pièce jointe, une vidéo ou une sauvegarde. Le volume seul ne démontre pas une fuite de données.");
                f.facts.put("Volume observé",w.total()+" octets");f.facts.put("Seuil",s.uploadMiB+" Mio / 5 minutes");f.evidence.addAll(w.evidence());if(previous!=null)f.evidence.add(previous.id);sink.emit(f);
            }
        }
        if(!appKey.isEmpty()&&s.failures&&e.closed&&(previous==null||!previous.closed)&&
           (e.error!=0||e.result.startsWith("erreur")||e.result.equals("injoignable"))){
            String key=appKey+":"+e.destination;Window w=failures.get(key);if(w==null){w=new Window();failures.put(key,w);}w.add(time,1,e.id);
            if(w.total()>=s.failureCount){
                Finding f=finding(e,"failures","anomaly","attention","Échecs de connexion répétés",key,
                    "Plusieurs flux distincts de cette application vers la même destination se sont terminés avec une erreur en cinq minutes.",
                    "Vérifier le réseau, le serveur et le relais local. Les fermetures normales et les réinitialisations de démarrage sans erreur sont exclues.");
                f.facts.put("Échecs",String.valueOf(w.total()));f.facts.put("Seuil",s.failureCount+" / 5 minutes");f.facts.put("Destination contactée",e.destination);f.evidence.addAll(w.evidence());sink.emit(f);
            }
        }
        Flow next=new Flow();next.tx=e.tx;next.elapsed=time;next.id=e.id;next.closed=e.closed;next.portTrace=portTrace;flows.put(e.flow,next);
    }
    private Finding finding(Event e,String rule,String type,String severity,String title,String subject,String explanation,String advice){
        Finding f=new Finding();f.rule=rule;f.type=type;f.severity=severity;f.title=title;f.actor=e.actor;f.subject=subject;f.explanation=explanation;f.advice=advice;f.eventId=e.id;f.wall=e.wall;f.evidence.add(e.id);
        long at=e.elapsed>=0?time:Math.max(0,e.wall);
        // Group repeated observations, including A/AAAA lookups, instead of one alert per row.
        f.key=epoch+":"+rule+":"+subject+":"+(at/(rule.equals("volume")||rule.equals("failures")?WINDOW:GROUP));
        return f;
    }
    private void prune(){
        for(Iterator<Flow> i=flows.values().iterator();i.hasNext();){Flow f=i.next();if(time-f.elapsed>(f.closed?GROUP:3600000))i.remove();}
        for(Map<String,Window> windows:Arrays.asList(uploads,failures))for(Iterator<Window> i=windows.values().iterator();i.hasNext();)if(time-i.next().touched>WINDOW)i.remove();
        // Fixed protection against unbounded transient state. Missing baselines never count as new bytes.
        if(flows.size()>10000)flows.clear();
        if(networks.size()>2000)networks.clear();
    }
    private static String canonical(String[] values){TreeSet<String> set=new TreeSet<>();for(String s:values)if(s!=null&&!s.trim().isEmpty())set.add(s.trim().toLowerCase(Locale.ROOT));return String.join(", ",set);}
    public static String domain(String raw){
        if(raw==null)return "";String s=raw.trim().toLowerCase(Locale.ROOT);if(s.endsWith("."))s=s.substring(0,s.length()-1);
        try{s=IDN.toASCII(s,IDN.USE_STD3_ASCII_RULES);}catch(IllegalArgumentException e){return "";}
        if(s.length()>253||!s.contains(".")||s.matches("[0-9.]+"))return "";
        for(String label:s.split("\\.",-1))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))return "";
        return s;
    }
    public static boolean within(String q,String domain){return q.equals(domain)||q.endsWith("."+domain);}
    public static String researchMatch(String q){
        for(String suffix:new String[]{"c2s.ic.gov","sc2s.sgov.gov","api.aws.ic.gov","api.aws.scloud"})if(within(q,suffix))return suffix+" (rapport fourni)";
        if(q.matches("bedrock-runtime-fips\\.[a-z0-9-]+\\.amazonaws\\.com(?:\\.cn)?"))return "bedrock-runtime-fips (rapport fourni)";
        return "";
    }
}