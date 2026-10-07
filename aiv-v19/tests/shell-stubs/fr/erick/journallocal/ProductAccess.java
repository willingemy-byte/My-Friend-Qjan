package fr.erick.journallocal;
final class ProductAccess {
 static boolean enabled=true;
 static int verifiedTier(){return enabled?3:1;}
 static void requireFounder(){if(!enabled)throw new SecurityException("Founder required");}
}
