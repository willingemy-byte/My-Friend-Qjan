package fr.erick.journallocal;

import java.util.*;
/** Pure Java predicates. Unknown evidence is never converted into false. */
public final class CoherenceRules {
    public enum Match { YES, NO, UNKNOWN }
    public static final class Facts {
        public boolean network; public int uid=-1;
        public Boolean internet, contacts, inventoried, system, visible, knownDestination, whitelisted;
        public String action="",sni="";public Long txBytes;
        public Boolean contactWindowComplete, contactSyncExpected, contactSyncSeen;
    }
    public static final class Rule {
        public long id;public String name,decision;public int priority,version;public boolean enabled;
        public long threshold=10485760;public List<String> domains=Collections.emptyList();
    }
    public static boolean domainMatches(String host,String domain){
        host=host.toLowerCase(Locale.ROOT);domain=domain.toLowerCase(Locale.ROOT);
        if(host.endsWith("."))host=host.substring(0,host.length()-1);
        if(domain.endsWith("."))domain=domain.substring(0,domain.length()-1);
        return !domain.isEmpty()&&(host.equals(domain)||host.endsWith("."+domain));
    }
    private static Match value(Boolean b){return b==null?Match.UNKNOWN:b?Match.YES:Match.NO;}
    public static Match evaluate(Rule r,Facts f){
        if(!r.enabled)return Match.NO;
        switch(r.name){
        case "R1": if(!f.network)return Match.NO;if(f.internet==null||f.knownDestination==null)return Match.UNKNOWN;return value(f.internet&&!f.knownDestination);
        case "R2": if(f.contacts==null)return Match.UNKNOWN;if(!f.contacts)return Match.NO;
            if(!Boolean.TRUE.equals(f.contactSyncExpected)||!Boolean.TRUE.equals(f.contactWindowComplete))return Match.UNKNOWN;
            return f.contactSyncSeen==null?Match.UNKNOWN:value(!f.contactSyncSeen);
        case "R3": if(f.uid<0)return Match.UNKNOWN;if(f.uid%100000!=1000||!(f.action.equals("CAMERA")||f.action.equals("MICROPHONE")))return Match.NO;
            return f.system==null||f.visible==null?Match.UNKNOWN:value(f.system&&!f.visible);
        case "R4": if(!f.network)return Match.NO;return f.inventoried==null?Match.UNKNOWN:value(!f.inventoried);
        case "R5": if(!f.network)return Match.NO;if(f.txBytes==null)return Match.UNKNOWN;if(f.txBytes<=r.threshold)return Match.NO;return f.whitelisted==null?Match.UNKNOWN:value(!f.whitelisted);
        case "R6": if(!f.network||r.domains.isEmpty())return Match.NO;if(f.sni.isEmpty())return Match.UNKNOWN;
            for(String d:r.domains)if(domainMatches(f.sni,d))return Match.YES;return Match.NO;
        default: return Match.UNKNOWN;
        }
    }
    public static List<Rule> sorted(List<Rule> rules){List<Rule> out=new ArrayList<>(rules);Collections.sort(out,(a,b)->{int p=Integer.compare(b.priority,a.priority);if(p!=0)return p;int n=a.name.compareTo(b.name);return n!=0?n:Integer.compare(b.version,a.version);});return out;}
}