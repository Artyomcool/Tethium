package dev.tethium.mobile;

import android.content.*;
import android.content.pm.PackageInstaller;

/** Explicit PendingIntent receiver; only the system holding that grant can supply a result. */
public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent result){
        String pkg=result.getStringExtra("package");if(pkg==null)return;
        int status=result.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        var edit=AppUpdates.prefs(c).edit();
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirmation=result.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null){edit.putString("confirm."+pkg,confirmation.toUri(0)).putString("status."+pkg,"Нужно подтверждение Android").apply();AppUpdates.notify(c,pkg,"Подтвердите установку в разделе «Приложения»");}
        }else{
            edit.remove("install."+pkg).remove("confirm."+pkg).putString("status."+pkg,status==PackageInstaller.STATUS_SUCCESS?"Установлено":result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if(status!=PackageInstaller.STATUS_SUCCESS)edit.putString("failed."+pkg,result.getStringExtra("hash"));else {edit.remove("failed."+pkg);var installed=AppUpdates.installed(c,pkg);if(installed!=null)edit.putString("installedHash."+pkg,result.getStringExtra("hash")).putLong("installedAt."+pkg,installed.lastUpdateTime);}
            edit.apply();
        }
    }
}
