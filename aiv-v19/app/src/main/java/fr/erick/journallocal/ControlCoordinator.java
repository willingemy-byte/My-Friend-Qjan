package fr.erick.journallocal;

/** One lease shared by permission cleanup, normalization, restoration and package control. */
final class ControlCoordinator {
    private static boolean held;
    static synchronized boolean acquire(){if(held)return false;held=true;return true;}
    static synchronized void release(){held=false;}
}
