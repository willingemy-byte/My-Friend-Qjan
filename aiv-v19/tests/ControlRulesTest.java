package fr.erick.journallocal;

public final class ControlRulesTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        check(ControlRules.validPackage("com.samsung.knox.securefolder"));
        for(String value:new String[]{"", "android", "x.y;rm", "x.y\n", "../package", "x.y --user 10", "x.y'"})check(!ControlRules.validPackage(value));
        check(ControlRules.validAction("stop"));check(ControlRules.validAction("disable"));check(!ControlRules.validAction("delete"));
        check(!ControlRules.reached("disable",1,true));check(ControlRules.reached("disable",3,false));
        check(ControlRules.reached("stop",0,true));check(!ControlRules.reached("stop",3,false));
        check("no_effect".equals(ControlRules.outcome(0,false)));check("refused".equals(ControlRules.outcome(1,false)));
        check("confirmed".equals(ControlRules.outcome(1,true))); // observed state wins; raw error remains recorded
        check("default-state".equals(ControlRules.enabledCommand(0)));check("enable".equals(ControlRules.enabledCommand(1)));check("disable-until-used".equals(ControlRules.enabledCommand(4)));
        boolean rejected=false;try{ControlRules.enabledCommand(999);}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        check(!ControlRules.sameIdentity("a","b"));check(!ControlRules.sameIdentity("",""));check(ControlRules.sameIdentity("a","a"));
        check(ControlCoordinator.acquire());check(!ControlCoordinator.acquire());ControlCoordinator.release();check(ControlCoordinator.acquire());ControlCoordinator.release();
        System.out.println("PASS target validation, observed results, exact restoration modes, shared execution lease");
    }
}
