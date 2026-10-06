package fr.erick.journallocal;

/** Pure policy: observer backlog must not tear down the user's VPN transport. */
final class NetworkCapturePolicy {
    private NetworkCapturePolicy(){}

    static boolean shouldStopNative(boolean stopRequested,boolean reconfigure,long observerStallMs){
        return stopRequested||reconfigure;
    }

    static boolean journalDelayed(long observerDelayMs){
        return observerDelayMs>=3000;
    }
}
