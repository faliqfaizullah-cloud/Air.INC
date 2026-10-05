package com.airinc.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.Iterator;

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences sp;
    private long lastScan;
    private String pendingGo;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("air", 0);
        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFFF2F2F4);

        final Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void hap(String t) {
                if (vib == null || !vib.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 29) {
                    int e = t.equals("tick") ? VibrationEffect.EFFECT_TICK : t.equals("heavy") ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
                    vib.vibrate(VibrationEffect.createPredefined(e));
                } else if (Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createOneShot(t.equals("heavy") ? 45 : t.equals("tick") ? 8 : 18, 160));
                } else vib.vibrate(18);
            }
        }, "AirHaptic");
        web.addJavascriptInterface(new Bridge(), "AirPlayer");

        MediaService.listener = new MediaService.Listener() {
            @Override public void state(String j) { run("window.airState&&airState(" + j + ")"); }
            @Override public void alarm() { run("window.airAlarm&&airAlarm()"); }
        };
        pendingGo = getIntent().getStringExtra("go");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) { runOnUiThread(() -> recreate()); return true; }
            @Override public void onPageFinished(WebView v, String u) {
                if (pendingGo != null) { run("window.airGo&&airGo('" + pendingGo + "')"); pendingGo = null; }
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        if (hasPerm()) scanLib(); else askPermissions();
        // refresh the headphone card when devices connect / disconnect or report a battery level
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] a) { run("window.airHp&&airHp()"); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] a) { run("window.airHp&&airHp()"); }
        }, null);
        BroadcastReceiver batt = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int lv = i.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
                    if (d != null && lv >= 0) sp.edit().putInt("hp_bat_" + d.getAddress(), lv).apply();
                } catch (Throwable t) { }
                run("window.airHp&&airHp()");
            }
        };
        IntentFilter bf = new IntentFilter("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batt, bf, Context.RECEIVER_EXPORTED); else registerReceiver(batt, bf);
    }

    private void run(final String js) { runOnUiThread(() -> web.evaluateJavascript(js, null)); }

    // ---------- permissions + scan ----------
    private boolean hasPerm() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        else if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.BLUETOOTH_CONNECT}, 1);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        run("window.airHp&&airHp()");
        if (hasPerm()) { run("window.airPerm&&airPerm()"); scanLib(); }
    }

    private void scanLib() {
        if (!hasPerm()) return;
        lastScan = System.currentTimeMillis();
        Library.scan(this, (arr, done) -> runOnUiThread(() -> {
            web.evaluateJavascript("window.airLib&&airLib(" + arr.toString() + "," + done + ")", null);
            if (MediaService.running) startService(new Intent(MainActivity.this, MediaService.class).setAction("reload"));
        }));
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i); setIntent(i);
        String g = i.getStringExtra("go");
        if (g != null) run("window.airGo&&airGo('" + g + "')");
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasPerm() && System.currentTimeMillis() - lastScan > 10 * 60 * 1000) scanLib();
    }

    // ---------- JS bridge ----------
    class Bridge {
        @JavascriptInterface public void cmd(String a, String json) {
            Intent i = new Intent(MainActivity.this, MediaService.class).setAction(a);
            try {
                JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
                Iterator<String> k = o.keys();
                while (k.hasNext()) {
                    String key = k.next(); Object v = o.get(key);
                    if (v instanceof Integer) i.putExtra(key, ((Integer) v).intValue());
                    else if (v instanceof Long) i.putExtra(key, ((Long) v).longValue());
                    else if (v instanceof Double) i.putExtra(key, ((Double) v).doubleValue());
                    else if (v instanceof Boolean) i.putExtra(key, ((Boolean) v).booleanValue());
                    else if (v instanceof org.json.JSONArray) {
                        org.json.JSONArray ja = (org.json.JSONArray) v; int[] arr = new int[ja.length()];
                        for (int n = 0; n < arr.length; n++) arr[n] = ja.optInt(n);
                        i.putExtra(key, arr);
                    }
                }
            } catch (Exception e) { }
            if (Build.VERSION.SDK_INT >= 26 && !MediaService.running) startForegroundService(i); else startService(i);
        }
        @JavascriptInterface public String headphones() { return Headphones.info(MainActivity.this); }
        @JavascriptInterface public void setPref(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
        @JavascriptInterface public void askBt() { runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2); }); }
        @JavascriptInterface public void openBt() { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        @JavascriptInterface public String state() { return MediaService.lastState; }
        @JavascriptInterface public String library() { return Library.load(MainActivity.this).toString(); }
        @JavascriptInterface public void scan() { runOnUiThread(() -> scanLib()); }
        @JavascriptInterface public boolean perm() { return hasPerm(); }
        @JavascriptInterface public void askPerm() { runOnUiThread(() -> askPermissions()); }
        @JavascriptInterface public String stats() { return MediaService.readStats(sp).toString(); }
        @JavascriptInterface public String eqInfo() { return MediaService.eqInfo(MainActivity.this); }
        @JavascriptInterface public void theme(String m) { sp.edit().putString("theme", m).apply(); AirWidget.refreshAll(MainActivity.this); }
        @JavascriptInterface public void battery() {
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }
        @JavascriptInterface public void alarm(int h, int m, boolean on) { Alarms.schedule(MainActivity.this, h, m, on); AirWidget.refreshAll(MainActivity.this); }
        @JavascriptInterface public String getAlarm() {
            return "{\"on\":" + sp.getBoolean("alarm_on", false) + ",\"h\":" + sp.getInt("alarm_h", 10) + ",\"m\":" + sp.getInt("alarm_m", 15) + "}";
        }
    }

    @Override protected void onDestroy() { MediaService.listener = null; super.onDestroy(); }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.airBack&&window.airBack()", v -> {
            if ("true".equals(v)) return;
            finish();
        });
    }
}
