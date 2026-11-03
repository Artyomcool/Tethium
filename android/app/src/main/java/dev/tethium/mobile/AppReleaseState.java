package dev.tethium.mobile;

/** Same-version development APKs are offered explicitly, never auto-reinstalled. */
final class AppReleaseState {
    enum Action { INSTALL, UPDATE, REINSTALL, CURRENT, NEWER_INSTALLED }
    static Action action(boolean installed,long current,long offered,String offeredHash,String installedHash,boolean receiptCurrent){
        if(!installed)return Action.INSTALL;
        if(current<offered)return Action.UPDATE;
        if(current>offered)return Action.NEWER_INSTALLED;
        return receiptCurrent&&offeredHash.equals(installedHash)?Action.CURRENT:Action.REINSTALL;
    }
}
