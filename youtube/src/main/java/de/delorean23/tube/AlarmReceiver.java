package de.delorean23.tube;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Wecker klingelt / nach Neustart wieder planen. Created by DeLorean23 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_RING = "de.delorean23.tube.ALARM_RING";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (ACTION_RING.equals(a)) {
            int id = intent.getIntExtra("id", 0);
            Alarms.afterRing(context, id);
            Intent s = new Intent(context, AlarmService.class)
                    .setAction(AlarmService.ACTION_RING).putExtra("id", id);
            try {
                ContextCompat.startForegroundService(context, s);
            } catch (Throwable t) {
                android.util.Log.e("DeLoreanTube", "Wecker", t);
            }
        } else {
            // BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED
            Alarms.scheduleAll(context);
        }
    }
}
