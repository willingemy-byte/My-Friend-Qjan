package fr.erick.journallocal;

/** Pure validation gate for a server receipt before any local journal purge can become eligible. */
public final class ArchiveReceiptRules {
    private ArchiveReceiptRules(){}

    public static String mismatch(String state,long received,long expected,
                                  String serverSha,String clientSha,
                                  long serverFirst,long localFirst,
                                  long serverLast,long localLast){
        if(!"VERIFIED".equals(state))return "state";
        if(received!=expected)return "received_count";
        if(clientSha==null||serverSha==null||!clientSha.equalsIgnoreCase(serverSha))return "segment_sha256";
        if(serverFirst!=localFirst)return "first_event_id";
        if(serverLast!=localLast)return "last_event_id";
        return "";
    }

    public static boolean verified(String state,long received,long expected,
                                   String serverSha,String clientSha,
                                   long serverFirst,long localFirst,
                                   long serverLast,long localLast){
        return mismatch(state,received,expected,serverSha,clientSha,serverFirst,localFirst,serverLast,localLast).isEmpty();
    }
}
