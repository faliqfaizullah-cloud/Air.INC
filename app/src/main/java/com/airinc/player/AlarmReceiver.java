package com.airinc.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Intent s = new Intent(c, MediaService.class).setAction("alarm");
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
    }
}
