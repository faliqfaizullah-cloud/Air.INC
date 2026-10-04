package com.airinc.player;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> chooser;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFFF2F2F2);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, 7);
                return true;
            }
        });
        final Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void hap(String t) {
                if (vib == null || !vib.hasVibrator()) return;
                if (Build.VERSION.SDK_INT >= 29) {
                    int e = t.equals("tick") ? VibrationEffect.EFFECT_TICK
                          : t.equals("heavy") ? VibrationEffect.EFFECT_HEAVY_CLICK
                          : VibrationEffect.EFFECT_CLICK;
                    vib.vibrate(VibrationEffect.createPredefined(e));
                } else if (Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createOneShot(t.equals("heavy") ? 45 : t.equals("tick") ? 8 : 18, 160));
                } else vib.vibrate(18);
            }
        }, "AirHaptic");
        MediaService.listener = (cmd, pos) -> runOnUiThread(() ->
            web.evaluateJavascript("window.airMedia&&airMedia('" + cmd + "'," + pos + ")", null));
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void data(String json) {
                getSharedPreferences("air", 0).edit().putString("json", json).apply();
                AirWidget.refreshAll(MainActivity.this);
            }
            @JavascriptInterface public void update(String title, String artist, boolean playing, long pos, long dur, String art) {
                Intent i = new Intent(MainActivity.this, MediaService.class).setAction("update")
                    .putExtra("title", title).putExtra("artist", artist)
                    .putExtra("playing", playing).putExtra("pos", pos).putExtra("dur", dur).putExtra("art", art);
                if (Build.VERSION.SDK_INT >= 26 && !MediaService.running) startForegroundService(i);
                else startService(i);
            }
        }, "AirMedia");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == 7 && chooser != null) {
            Uri[] r = null;
            if (res == RESULT_OK && d != null) {
                if (d.getClipData() != null) {
                    int n = d.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int i = 0; i < n; i++) r[i] = d.getClipData().getItemAt(i).getUri();
                } else if (d.getData() != null) r = new Uri[]{d.getData()};
            }
            if (r != null) extract(r);
            chooser.onReceiveValue(r);
            chooser = null;
        }
    }

    /** Reads title/artist/album and the embedded album art of each picked song. */
    private void extract(final Uri[] uris) {
        new Thread(() -> {
            JSONArray out = new JSONArray();
            for (Uri u : uris) {
                try {
                    String name = null; long size = -1;
                    Cursor c = getContentResolver().query(u, null, null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) {
                            int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                            if (ni >= 0) name = c.getString(ni);
                            if (si >= 0) size = c.getLong(si);
                        }
                        c.close();
                    }
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(this, u);
                    JSONObject o = new JSONObject();
                    o.put("key", name + "|" + size);
                    o.put("title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
                    o.put("artist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
                    o.put("album", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
                    byte[] pic = r.getEmbeddedPicture();
                    if (pic != null) {
                        File f = new File(getCacheDir(), "art_" + Integer.toHexString((name + size).hashCode()) + ".img");
                        FileOutputStream fo = new FileOutputStream(f); fo.write(pic); fo.close();
                        o.put("art", "file://" + f.getAbsolutePath());
                    }
                    r.release();
                    out.put(o);
                } catch (Exception e) { /* skip files without readable tags */ }
            }
            final String js = "window.airMeta&&airMeta(" + out.toString() + ")";
            runOnUiThread(() -> web.evaluateJavascript(js, null));
        }).start();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onDestroy() { MediaService.listener = null; super.onDestroy(); }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.airBack&&window.airBack()", v -> {
            if ("true".equals(v)) return;
            finish();
        });
    }
}
