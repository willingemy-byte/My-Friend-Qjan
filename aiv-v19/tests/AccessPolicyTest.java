package fr.erick.journallocal;
public final class AccessPolicyTest {
    private static void check(boolean value) {if(!value)throw new AssertionError("Access policy regression");}
    public static void main(String[] args) {
        check(AccessPolicy.allows("journal.read",1));
        check(AccessPolicy.allows("journal.read",2));
        check(AccessPolicy.allows("journal.read",3));
        check(!AccessPolicy.allows("fleet.view",2));
        check(AccessPolicy.allows("fleet.view",3));
        check(!AccessPolicy.allows("journal.read",4));
        check(!AccessPolicy.allows("journal.read",0));
        check(!AccessPolicy.allows("journal.read",Integer.MAX_VALUE));
        check(!AccessPolicy.allows("unknown.service",2));
        check(!AccessPolicy.allows(null,2));
        check(!AccessPolicy.allowsMinimum(2,1));
        check(AccessPolicy.allowsMinimum(2,2));
        check(AccessPolicy.allowsMinimum(1,2));
        check(!AccessPolicy.allowsMinimum(3,2));
        System.out.println("PASS tier access, paid minimum, TI tier and unknown services");
    }
}
