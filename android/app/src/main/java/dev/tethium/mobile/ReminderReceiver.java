package dev.tethium.mobile;

import android.content.*;

/** Alarm at a due time and the return of a swiped reminder; not exported, only our own PendingIntents reach it. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){Reminders.reschedule(c);}
}
