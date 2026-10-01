package fr.erick.journallocal;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Cooperative limits; an individual SQLite operation may exceed the time slice. */
public final class WorkBudget {
    public interface Clock { long millis(); }
    public static final int EVENTS=AivConfig.WORK_EVENTS, VERIFY_ROWS=AivConfig.WORK_VERIFY_ROWS, STATS_ROWS=AivConfig.WORK_STATS_ROWS;
    public static final long SLICE_MS=AivConfig.WORK_SLICE_MS, MIN_PAUSE_MS=AivConfig.WORK_MIN_PAUSE_MS;
    private final Clock clock;private final BooleanSupplier cancelled;
    private final long started,maxMillis;private final int maxItems;private int items;
    public WorkBudget(Clock clock,BooleanSupplier cancelled,int maxItems,long maxMillis){this.clock=clock;this.cancelled=cancelled;this.maxItems=maxItems;this.maxMillis=maxMillis;started=clock.millis();}
    public boolean next(){checkCancelled();if(items>=maxItems||(items>0&&clock.millis()-started>=maxMillis))return false;items++;return true;}
    public void checkCancelled(){if(cancelled.getAsBoolean())throw new CancellationException("Travail AIV arrêté");}
    public static long pauseAfter(long elapsed){long ms=Math.max(0,elapsed);return Math.max(MIN_PAUSE_MS,ms>Long.MAX_VALUE/9?Long.MAX_VALUE:ms*9);}
}