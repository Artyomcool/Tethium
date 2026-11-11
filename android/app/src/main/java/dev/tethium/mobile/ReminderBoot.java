package dev.tethium.mobile;

import android.content.*;

/** After a restart or an update of the app the alarms are gone; the list on the phone sets them again. */
public final class ReminderBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        String action=intent.getAction();
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))Reminders.init(c);
    }
}
