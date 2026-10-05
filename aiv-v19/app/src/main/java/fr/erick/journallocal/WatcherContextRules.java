package fr.erick.journallocal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Pure-Java classification of frontend/backend coherence. */
public final class WatcherContextRules {
    public enum State { USER_CORROBORATED, BACKGROUND_EXPECTED, UNEXPLAINED, CONTRADICTORY, UNKNOWN }

    private static final Set<String> SENSITIVE=new HashSet<>(Arrays.asList(
        "READ_CLIPBOARD","RECORD_AUDIO","CAMERA","READ_CONTACTS","READ_SMS","FINE_LOCATION"
    ));
    private static final Set<String> STRONGLY_USER_CONTEXTUAL=new HashSet<>(Arrays.asList(
        "READ_CLIPBOARD","RECORD_AUDIO","CAMERA"
    ));
    private static final Set<String> USER_EVENTS=new HashSet<>(Arrays.asList(
        "CLICK","TEXT_CHANGE","SELECTION","FOCUS"
    ));

    private WatcherContextRules(){}

    public static boolean sensitive(String operation){return SENSITIVE.contains(operation);}
    public static boolean userEvent(String eventKind){return USER_EVENTS.contains(eventKind);}

    public static State classify(String operation,boolean sameClock,boolean nearbyFrontend,
                                 boolean sameActor,boolean userInteraction){
        if(!sensitive(operation))return State.BACKGROUND_EXPECTED;
        if(!sameClock)return State.UNKNOWN;
        if(!nearbyFrontend)return State.UNEXPLAINED;
        if(userInteraction&&sameActor)return State.USER_CORROBORATED;
        if(userInteraction&&!sameActor&&STRONGLY_USER_CONTEXTUAL.contains(operation))return State.CONTRADICTORY;
        return State.UNEXPLAINED;
    }
}
