package fr.erick.journallocal;

/** Exclusive home groups use Android identity attribution: reserved/shared/non-unique, system unique, user unique. */
public final class CoherenceGroups {
    private CoherenceGroups(){}
    public static String group(boolean systemApp,int uid,int candidates){
        boolean reserved=uid<0 || uid%100000<10000;
        if(reserved||candidates!=1)return "android";
        return systemApp?"system":"user";
    }
    public static boolean attributable(String category,int uid,int candidates,String pkg){
        return ("trafic".equals(category)||"dns".equals(category))&&uid>=0&&uid%100000>=10000
            &&candidates==1&&pkg!=null&&!pkg.trim().isEmpty();
    }
    public static Integer score(Object value){
        if(!(value instanceof Number))return null;
        double n=((Number)value).doubleValue();
        if(Double.isNaN(n)||Double.isInfinite(n))return null;
        return (int)Math.round(Math.max(0,Math.min(100,n)));
    }
    public static final class Stats {
        public int total,evaluated,unknown,below50,below70,findings;
        public long sum;
        public Integer minimum;
        public void add(Integer score,int issues){
            total++;findings+=issues;
            if(score==null){unknown++;return;}
            int value=Math.max(0,Math.min(100,score));
            evaluated++;sum+=value;
            if(minimum==null||value<minimum)minimum=value;
            if(value<50)below50++;
            if(value<70)below70++;
        }
        public Integer mean(){return evaluated==0?null:(int)Math.round((double)sum/evaluated);}
    }
}