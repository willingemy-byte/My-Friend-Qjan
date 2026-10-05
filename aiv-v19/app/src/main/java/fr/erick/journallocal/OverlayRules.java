package fr.erick.journallocal;
import org.json.*;
/** Shared status and alert selection, independent of Android rendering. */
final class OverlayRules {
    static boolean healthy(boolean accessibility,boolean collector,boolean correlation,boolean appops,boolean visual,boolean vpnExpected,boolean vpn,boolean coverageGap){return !coverageGap&& accessibility&&collector&&correlation&&appops&&visual&&(!vpnExpected||vpn);}
    static JSONArray visible(JSONArray alerts){JSONArray out=new JSONArray();for(int i=0;i<Math.min(2,alerts.length());i++)out.put(alerts.optJSONObject(i));return out;}
    static int remaining(long unread,JSONArray shown){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,unread-shown.length()));}
}
