package fr.erick.journallocal;
public final class AivApplication extends android.app.Application {
 @Override public void onCreate(){super.onCreate();ProductAccess.initialize(this);}
}
