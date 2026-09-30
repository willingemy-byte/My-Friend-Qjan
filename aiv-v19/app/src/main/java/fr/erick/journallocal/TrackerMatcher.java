package fr.erick.journallocal;

import java.net.IDN;
import java.util.*;
import java.util.regex.*;

/** Pure, offline matcher. A signature hit is evidence of a resemblance, not of content or intent. */
public final class TrackerMatcher {
    public static final class Signature {
        public final int id; public final String name; final Pattern network,code;
        public Signature(int id,String name,String network,String code){this.id=id;this.name=name;this.network=compile(network,true);this.code=compile(code,false);}
        private static Pattern compile(String s,boolean network){
            if(s==null||s.trim().isEmpty()||s.length()>12000)return null;
            try{return Pattern.compile(s,network?Pattern.CASE_INSENSITIVE:0);}catch(PatternSyntaxException e){return null;}
        }
    }
    public static final class Hit {
        public final Signature signature; public final boolean boundary;
        Hit(Signature s,boolean b){signature=s;boundary=b;}
    }
    private final List<Signature> signatures;
    public TrackerMatcher(List<Signature> s){signatures=Collections.unmodifiableList(new ArrayList<>(s));}
    public static String hostname(String value){
        if(value==null||value.length()>254)return "";
        String h=value.trim().toLowerCase(Locale.ROOT);if(h.endsWith("."))h=h.substring(0,h.length()-1);
        try{h=IDN.toASCII(h,IDN.USE_STD3_ASCII_RULES);}catch(IllegalArgumentException e){return "";}
        if(h.length()>253||!h.contains(".")||h.matches("[0-9.]+")||h.contains(":"))return "";
        for(String label:h.split("\\.",-1))if(label.isEmpty()||label.length()>63||label.startsWith("-")||label.endsWith("-"))return "";
        return h;
    }
    public List<Hit> network(String value){
        String h=hostname(value);List<Hit> result=new ArrayList<>();if(h.isEmpty())return result;
        for(Signature s:signatures)if(s.network!=null){Matcher m=s.network.matcher(h);boolean found=false,boundary=false;
            while(m.find()){found=true;if((m.start()==0||h.charAt(m.start()-1)=='.')&&m.end()==h.length())boundary=true;}
            if(found)result.add(new Hit(s,boundary));
        }return result;
    }
    public Set<Integer> code(Collection<String> classNames){
        List<String> normalized=new ArrayList<>();for(String c:classNames)if(c.length()<=2048)normalized.add(c.replace('/','.'));
        Set<Integer> hits=new TreeSet<>();for(Signature s:signatures)if(s.code!=null)for(String c:normalized){
            if(s.code.matcher(c).find()){hits.add(s.id);break;}
        }return hits;
    }
}
