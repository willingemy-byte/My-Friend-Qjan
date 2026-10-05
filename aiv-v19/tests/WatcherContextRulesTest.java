package fr.erick.journallocal;

public final class WatcherContextRulesTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        check(WatcherContextRules.classify("READ_CLIPBOARD",true,true,true,true)==WatcherContextRules.State.USER_CORROBORATED,"clipboard same actor interaction");
        check(WatcherContextRules.classify("READ_CLIPBOARD",true,true,false,true)==WatcherContextRules.State.CONTRADICTORY,"clipboard other app interaction");
        check(WatcherContextRules.classify("READ_CLIPBOARD",true,false,false,false)==WatcherContextRules.State.UNEXPLAINED,"clipboard without frontend");
        check(WatcherContextRules.classify("READ_CLIPBOARD",false,false,false,false)==WatcherContextRules.State.UNKNOWN,"clipboard without common clock");
        check(WatcherContextRules.classify("VIBRATE",true,false,false,false)==WatcherContextRules.State.BACKGROUND_EXPECTED,"non-sensitive operation");
        check(WatcherContextRules.userEvent("TEXT_CHANGE"),"text change is user context");
        check(WatcherContextRules.userEvent("SELECTION"),"selection is user context");
        check(WatcherContextRules.sensitive("RECORD_AUDIO"),"microphone sensitive");
        System.out.println("WatcherContextRulesTest: "+checks+" checks passed");
    }
}
