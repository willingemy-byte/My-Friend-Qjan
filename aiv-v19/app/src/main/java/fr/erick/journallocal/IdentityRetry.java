package fr.erick.journallocal;

/** Monotonic deadlines: packet bursts must not consume all identity attempts. */
final class IdentityRetry {
    private static final long[] DELAYS={0,25,100,300,750,1500,3000,6000};
    private final long started;
    private int attempts;
    private boolean finished;
    IdentityRetry(long now){started=now;}
    boolean begin(long now){
        if(done(now)||now-started<DELAYS[attempts])return false;
        attempts++;return true;
    }
    boolean done(long now){return finished||attempts>=DELAYS.length||now-started>10000;}
    void finish(){finished=true;}
    int attempts(){return attempts;}
}
