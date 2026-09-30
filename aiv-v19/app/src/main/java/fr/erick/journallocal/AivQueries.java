package fr.erick.journallocal;

/** Small read projections and resumable queries shared with the host regression tests. */
public final class AivQueries {
    private AivQueries(){}
    public static final String PAGE_ALL="SELECT id,event_id,rule_name,decision,timestamp_ms FROM decisions WHERE id<? ORDER BY id DESC LIMIT 50";
    public static final String PAGE_FILTER="SELECT id,event_id,rule_name,decision,timestamp_ms FROM decisions WHERE decision=? AND id<? ORDER BY id DESC LIMIT 50";
    public static final String STATS_BATCH="SELECT d.id,d.decision,e.app FROM decisions d JOIN events e ON e.id=d.event_id WHERE d.id>? ORDER BY d.id LIMIT ?";
    public static final String STATS_APPS="SELECT app,evaluated,findings,1.0*findings/evaluated AS ratio FROM aiv_app_stats ORDER BY findings DESC,app LIMIT 100";
    public static final String STATS_STATE="SELECT checkpoint,evaluated,findings FROM aiv_stats_state WHERE id=1";
    public static final String PROGRESS="SELECT checkpoint,chain_count,chain_head,(SELECT COALESCE(MAX(id),0) FROM events) AS latest_event_id,(SELECT COALESCE(MAX(id),0) FROM decisions) AS latest_decision_id FROM aiv_state WHERE id=1";
    public static final String VERIFY_PAGE="SELECT c.id,c.event_id,c.decision_id,c.kind,c.timestamp_ms,c.hash_prev,c.hash_self,c.payload,e.id,e.timestamp_ms,e.app,e.action,e.destination,e.transport,e.category,e.payload,e.search_text,e.hash_prev,e.hash_self,d.id,d.event_id,d.rule_name,d.decision,d.reason,d.timestamp_ms,d.enforcement,d.hash_prev,d.hash_self FROM journal_chain c LEFT JOIN events e ON c.kind='EVENT' AND e.id=c.event_id LEFT JOIN decisions d ON c.kind='DECISION' AND d.id=c.decision_id AND d.event_id=c.event_id WHERE c.id>? AND c.id<=? ORDER BY c.id LIMIT ?";
    public static final String COUNT_SEALED_EVENTS="SELECT COUNT(*) FROM events INDEXED BY events_time WHERE id<=?";
    public static final String COUNT_SNAPSHOT_DECISIONS="SELECT COUNT(*) FROM decisions INDEXED BY decisions_event WHERE id<=?";
}