package fr.erick.journallocal;
import org.json.*;
/** Shared status and alert selection, independent of Android rendering. */
final class OverlayRules {
    static boolean healthy(boolean accessibility,boolean collector,boolean correlation,boolean appops,boolean visual,boolean vpnExpected,boolean vpn,boolean coverageGap){return !coverageGap&& accessibility&&collector&&correlation&&appops&&visual&&(!vpnExpected||vpn);}
    static JSONArray visible(JSONArray alerts){JSONArray out=new JSONArray();for(int i=0;i<Math.min(2,alerts.length());i++)out.put(alerts.optJSONObject(i));return out;}
    static int remaining(long unread,JSONArray shown){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,unread-shown.length()));}
    static boolean coverageIncomplete(long lastGapMs){return lastGapMs>0;}
    static String highlightReason(JSONObject frontend,int window,String pkg,String clock,long now,boolean matched){
        if(frontend==null)return "NO_LINKED_ELEMENT";
        if(!"EVENT_NODE".equals(frontend.optString("element_source")))return "NO_EVENT_ELEMENT";
        if(window!=frontend.optInt("window_id",-2)||!pkg.equals(frontend.optString("package_name")))return "WINDOW_CHANGED";
        if(clock.isEmpty()||!clock.equals(frontend.optString("clock_scope_id")))return "CLOCK_SCOPE_CHANGED";
        long age=now-frontend.optLong("captured_elapsed_ms",-1);if(age<0||age>5000)return "ELEMENT_EXPIRED";
        JSONObject element=frontend.optJSONObject("element");JSONArray b=element==null?null:element.optJSONArray("bounds");
        if(b==null||b.length()!=4||b.optInt(2)<=b.optInt(0)||b.optInt(3)<=b.optInt(1))return "NO_VALID_BOUNDS";
        return matched?"READY":"ELEMENT_NOT_PRESENT";
    }
    static boolean matchesElement(JSONObject target,JSONObject current){
        if(target==null||current==null||!current.optBoolean("visible"))return false;
        for(String key:new String[]{"bounds","class","text","content_description"})if(!current.optString(key).equals(target.optString(key)))return false;
        String view=target.optString("view_id");return view.isEmpty()||view.equals(current.optString("view_id"));
    }
}
