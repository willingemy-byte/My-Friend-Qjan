package fr.erick.journallocal;

public final class ArchiveReceiptRulesTest {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        String sha="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check(ArchiveReceiptRules.verified("VERIFIED",50000,50000,sha,sha,1,1,50000,50000),"exact VERIFIED receipt rejected");
        check(!ArchiveReceiptRules.verified("UPLOADING",50000,50000,sha,sha,1,1,50000,50000),"upload completion allowed purge");
        check("received_count".equals(ArchiveReceiptRules.mismatch("VERIFIED",49999,50000,sha,sha,1,1,50000,50000)),"count mismatch missed");
        check("segment_sha256".equals(ArchiveReceiptRules.mismatch("VERIFIED",50000,50000,"bad",sha,1,1,50000,50000)),"SHA mismatch missed");
        check("first_event_id".equals(ArchiveReceiptRules.mismatch("VERIFIED",50000,50000,sha,sha,2,1,50000,50000)),"first id mismatch missed");
        check("last_event_id".equals(ArchiveReceiptRules.mismatch("VERIFIED",50000,50000,sha,sha,1,1,49999,50000)),"last id mismatch missed");
        check("segment_sha256".equals(ArchiveReceiptRules.mismatch("VERIFIED",50000,50000,null,sha,1,1,50000,50000)),"missing server SHA accepted");
        check(ArchiveReceiptRules.verified("VERIFIED",50000,50000,sha.toUpperCase(java.util.Locale.ROOT),sha,1,1,50000,50000),"hex case difference rejected");
        System.out.println("ArchiveReceiptRulesTest: "+checks+" checks passed");
    }
}
