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
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.media.MediaRecorder;
import androidx.core.content.FileProvider;
import java.io.File;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.Iterator;

public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences sp;
    private long lastScan;
    private String pendingGo;
    private MediaRecorder rec; private File recFile; private long recT0;

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
        if (c == 3) { run("window.airRecPerm&&airRecPerm(" + (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) + ")"); return; }
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


    // ---------- memories ----------
    private void pushMem(String id) { run("window.airMem&&airMem(" + Memories.list(this) + "," + (id == null ? "null" : "'" + id + "'") + ")"); }

    private void startCapture(String kind) {
        try {
            if (kind.equals("pick")) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                startActivityForResult(Intent.createChooser(i, "Choose a memory"), 13);
                return;
            }
            boolean vid = kind.equals("video");
            File f = new File(Memories.cap(this), (vid ? "v_" : "p_") + System.currentTimeMillis() + (vid ? ".mp4" : ".jpg"));
            sp.edit().putString("mem_pending", f.getAbsolutePath()).apply();
            Uri u = FileProvider.getUriForFile(this, "com.airinc.player.files", f);
            Intent i = new Intent(vid ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, u)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(android.content.ClipData.newRawUri("m", u));
            startActivityForResult(i, vid ? 12 : 11);
        } catch (Exception e) { run("window.airMemErr&&airMemErr('No camera app available')"); }
    }

    @Override protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code < 11 || code > 13) return;
        if (res != RESULT_OK) { String pp = sp.getString("mem_pending", null); if (pp != null) new File(pp).delete(); sp.edit().remove("mem_pending").apply(); run("window.airMemErr&&airMemErr('')"); return; }
        final int c = code; final Uri pick = data == null ? null : data.getData();
        new Thread(() -> {
            String id = null;
            if (c == 13) { if (pick != null) id = Memories.addPicked(this, pick); }
            else {
                String pp = sp.getString("mem_pending", null); sp.edit().remove("mem_pending").apply();
                if (pp != null) { File f = new File(pp); if (f.exists() && f.length() > 0) id = c == 12 ? Memories.addVideo(this, f) : Memories.addPhoto(this, f); }
            }
            if (id == null) run("window.airMemErr&&airMemErr('Could not save that memory')"); else pushMem(id);
        }).start();
    }

    private boolean recPerm() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

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
        @JavascriptInterface public void lyrics(String id, String t, String a, String al, int dur) {
            new Thread(() -> {
                String r;
                try { r = Lyrics.get(MainActivity.this, Long.parseLong(id), t, a, al, dur); } catch (Exception e) { r = "{\"err\":true}"; }
                run("window.airLyrics&&airLyrics('" + id + "'," + r + ")");
            }).start();
        }
        @JavascriptInterface public void lyricsRetry(String id) { try { new File(new File(getFilesDir(), "lyrics"), id + ".json").delete(); } catch (Exception e) { } }
        @JavascriptInterface public String memList() { return Memories.list(MainActivity.this); }
        @JavascriptInterface public void memCapture(String k) { runOnUiThread(() -> startCapture(k)); }
        @JavascriptInterface public String memText(String t, String x) { String id = Memories.addText(MainActivity.this, t, x); pushMem(id); return id == null ? "" : id; }
        @JavascriptInterface public void memEdit(String id, String t, String x) { Memories.update(MainActivity.this, id, t, x, null); pushMem(null); }
        @JavascriptInterface public void memFav(String id, boolean f) { Memories.update(MainActivity.this, id, null, null, f); pushMem(null); }
        @JavascriptInterface public void memDel(String id) { Memories.delete(MainActivity.this, id); pushMem(null); }
        @JavascriptInterface public void memShare(String id) {
            File f = Memories.find(MainActivity.this, id);
            if (f == null) return;
            runOnUiThread(() -> {
                try {
                    Uri u = FileProvider.getUriForFile(MainActivity.this, "com.airinc.player.files", f);
                    String n = f.getName(); String t = n.endsWith(".mp4") ? "video/mp4" : n.endsWith(".m4a") ? "audio/mp4" : "image/jpeg";
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(t).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share memory"));
                } catch (Exception e) { }
            });
        }
        @JavascriptInterface public String memRecStart() {
            if (!recPerm()) { runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3)); return "perm"; }
            try {
                recFile = new File(Memories.cap(MainActivity.this), "r_" + System.currentTimeMillis() + ".m4a");
                rec = new MediaRecorder();
                rec.setAudioSource(MediaRecorder.AudioSource.MIC);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioSamplingRate(44100); rec.setAudioEncodingBitRate(96000);
                rec.setMaxDuration(10 * 60 * 1000);
                rec.setOutputFile(recFile.getAbsolutePath());
                rec.prepare(); rec.start(); recT0 = System.currentTimeMillis();
                return "ok";
            } catch (Exception e) { try { if (rec != null) rec.release(); } catch (Exception x) { } rec = null; return "err"; }
        }
        @JavascriptInterface public double memAmp() { try { return rec == null ? 0 : Math.min(1, rec.getMaxAmplitude() / 14000.0); } catch (Exception e) { return 0; } }
        @JavascriptInterface public String memRecStop(boolean keep) {
            if (rec == null) return "";
            long dur = System.currentTimeMillis() - recT0; boolean ok = true;
            try { rec.stop(); } catch (Exception e) { ok = false; }
            try { rec.release(); } catch (Exception e) { }
            rec = null;
            if (!keep || !ok || dur < 600) { if (recFile != null) recFile.delete(); return ""; }
            String id = Memories.addVoice(MainActivity.this, recFile, dur);
            pushMem(id); return id == null ? "" : id;
        }
        @JavascriptInterface public void setNum(String k, double v) { sp.edit().putFloat(k, (float) v).apply(); }
        @JavascriptInterface public void setLong(String k, double v) { sp.edit().putLong(k, (long) v).apply(); }
        @JavascriptInterface public void share(String text) {
            runOnUiThread(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")));
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
            return "{\"on\":" + sp.getBoolean("alarm_on", false) + ",\"h\":" + sp.getInt("alarm_h", 10) + ",\"m\":" + sp.getInt("alarm_m", 15)
                + ",\"vol\":" + sp.getFloat("alarm_vol", .6f) + ",\"vib\":" + sp.getBoolean("alarm_vib", true) + ",\"aid\":" + sp.getLong("alarm_id", -1) + "}";
        }
    }

    @Override protected void onDestroy() { try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception e) { } MediaService.listener = null; super.onDestroy(); }

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
