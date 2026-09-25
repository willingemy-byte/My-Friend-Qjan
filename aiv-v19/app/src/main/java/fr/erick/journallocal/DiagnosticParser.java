package fr.erick.journallocal;
import java.time.*;
import java.util.regex.*;
import org.json.*;
/** Selected metadata only; raw log messages are never retained. */
final class DiagnosticParser {
    private static final Pattern LOG=Pattern.compile("^\\s*(\\d{10}\\.\\d{3,9}|(?:\\d{4}-)?\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3,9})\\s+(?:(\\d+|system|u\\d+_[as]\\d+)\\s+)?(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+([^:]{1,128}):\\s?(.*)$");
    private static final Pattern ENDPOINT=Pattern.compile("(?<![0-9A-Fa-f:.])(?:\\[([0-9A-Fa-f:]+)\\]|((?:[0-9]{1,3}\\.){3}[0-9]{1,3})):(\\d{1,5})(?![0-9])");
    static JSONObject parse(String line,int year,int offsetMinutes)throws Exception{
        if(line.length()>16384)return null;
        if(line.startsWith("{")&&JsonSyntax.valid(line)){
            JSONObject j=new JSONObject(line);if(!"journal-socket-evidence/1".equals(j.optString("schema")))return null;
            long time=j.optLong("timestamp_ms",-1);int pid=j.optInt("pid",-1),uid=j.optInt("uid",-1);if(time<0||pid<=0||uid<0)return null;
            String protocol=j.optString("protocol").toUpperCase(java.util.Locale.ROOT);if(!protocol.equals("TCP")&&!protocol.equals("UDP"))return null;
            String local=j.optString("local_ip"),remote=j.optString("remote_ip");int lp=j.optInt("local_port"),rp=j.optInt("remote_port");if(!numericIp(local)||!numericIp(remote)||lp<1||lp>65535||rp<1||rp>65535)return null;
            return EventStore.object("timestamp_ms",time,"pid",pid,"uid",uid,"tag","socket_owner_reported","time_basis","UTC déclaré dans le fichier","kind","socket_tuple_reported","protocol",protocol,"local_ip",local,"local_port",lp,"remote_ip",remote,"remote_port",rp,"process_name",safe(j.optString("process_name")),"source_label",safe(j.optString("source")),"scope","Propriété du socket rapportée par un fichier externe; origine et appareil non authentifiés par Journal local");
        }
        Matcher m=LOG.matcher(line);if(!m.matches())return null;String stamp=m.group(1),basis;long time;
        if(stamp.matches("\\d{10}\\.\\d+")){time=new java.math.BigDecimal(stamp).movePointRight(3).setScale(0,java.math.RoundingMode.DOWN).longValueExact();basis="epoch UTC";}
        else{boolean supplied=stamp.length()>5&&stamp.charAt(4)!='-';if(supplied)stamp=year+"-"+stamp;time=LocalDateTime.parse(stamp.replace(' ','T')).toInstant(ZoneOffset.ofTotalSeconds(offsetMinutes*60)).toEpochMilli();basis=supplied?"année et fuseau fournis à l’import":"fuseau fourni à l’import";}
        int uid=-1;if(m.group(2)!=null){String u=m.group(2);if(u.equals("system"))uid=1000;else if(u.matches("\\d+"))uid=Integer.parseInt(u);}
        JSONObject result=EventStore.object("timestamp_ms",time,"pid",Integer.parseInt(m.group(3)),"tid",Integer.parseInt(m.group(4)),"uid",uid,"tag",safe(m.group(6)),"time_basis",basis,"kind","logcat_writer","process_name",JSONObject.NULL,"scope","PID de l’émetteur de la ligne logcat; pas nécessairement le processus qui a ouvert le socket");
        JSONArray endpoints=new JSONArray();Matcher ep=ENDPOINT.matcher(m.group(7));while(ep.find()&&endpoints.length()<8){String ip=ep.group(1)!=null?ep.group(1):ep.group(2);int port=Integer.parseInt(ep.group(3));if(numericIp(ip)&&port>0&&port<=65535)endpoints.put(EventStore.object("ip",ip,"port",port));}result.put("endpoints_mentioned",endpoints);return result;
    }
    static String safe(String v){if(v==null)return "";String s=v.replaceAll("[^\\p{L}\\p{N}._:/ -]","?");return s.substring(0,Math.min(s.length(),160));}
    static boolean numericIp(String ip){try{if(ip.contains(":")){if(!ip.matches("[0-9A-Fa-f:]+"))return false;return java.net.InetAddress.getByName(ip) instanceof java.net.Inet6Address;}String[] p=ip.split("\\.",-1);if(p.length!=4)return false;for(String s:p)if(!s.matches("[0-9]{1,3}")||Integer.parseInt(s)>255)return false;return true;}catch(Exception e){return false;}}
    static boolean sameIp(String a,String b){try{return numericIp(a)&&numericIp(b)&&java.net.InetAddress.getByName(a).equals(java.net.InetAddress.getByName(b));}catch(Exception e){return false;}}
    static String match(JSONObject event,JSONObject diagnostic){
        JSONObject d=event.optJSONObject("details");if(d==null)return "temporal";String remote=d.optString("remote_ip"),local=d.optString("local_ip");int port=d.optInt("port",event.optInt("port")),uid=d.optInt("uid",-1);
        if("socket_tuple_reported".equals(diagnostic.optString("kind"))&&uid>=0&&diagnostic.optInt("uid",-1)==uid&&sameIp(remote,diagnostic.optString("remote_ip"))&&port==diagnostic.optInt("remote_port")&&sameIp(local,diagnostic.optString("local_ip"))&&d.optInt("local_port")==diagnostic.optInt("local_port")&&d.optString("protocol").equalsIgnoreCase(diagnostic.optString("protocol")))return "socket_tuple_reported";
        JSONArray endpoints=diagnostic.optJSONArray("endpoints_mentioned");if(endpoints!=null)for(int i=0;i<endpoints.length();i++){JSONObject ep=endpoints.optJSONObject(i);if(ep!=null&&sameIp(remote,ep.optString("ip"))&&port==ep.optInt("port"))return "endpoint_and_time";}return "temporal";
    }
}