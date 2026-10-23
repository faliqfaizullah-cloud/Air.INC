package com.airinc.player;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;

/** Schedules the system alarm clock that wakes the music player (used by the app and the Alarm widget). */
public class Alarms {
    public static void schedule(Context c, int h, int m, boolean on) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 77, new Intent(c, AlarmReceiver.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSharedPreferences("air", 0).edit().putBoolean("alarm_on", on).putInt("alarm_h", h).putInt("alarm_m", m).apply();
        if (!on) { am.cancel(pi); return; }
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, h); t.set(Calendar.MINUTE, m); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_MONTH, 1);
        PendingIntent show = PendingIntent.getActivity(c, 78, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(t.getTimeInMillis(), show), pi);
    }
}
