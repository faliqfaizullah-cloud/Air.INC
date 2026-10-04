package com.airinc.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Build;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/** Two minimalist widgets: Now Playing (pause / next / back) and Listening analytics. White or dark by theme. */
public abstract class AirWidget extends AppWidgetProvider {
    static final int ORANGE = 0xFFFF6A45;
    abstract int layout();
    abstract void bind(Context c, RemoteViews v, boolean dark);

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), layout());
            boolean d = dark(c);
            v.setInt(R.id.root, "setBackgroundResource", d ? R.drawable.w_bg_dark : R.drawable.w_bg_light);
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            bind(c, v, d);
            m.updateAppWidget(id, v);
        }
    }

    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<?> k : new Class<?>[]{Player.class, Stats.class}) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
            if (ids.length > 0) c.sendBroadcast(new Intent(c, k).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        }
    }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("air", 0); }

    static boolean dark(Context c) {
        String m = sp(c).getString("theme", "auto");
        if (m.equals("dark")) return true;
        if (m.equals("light")) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Decodes an album-art file to a centre-cropped square of at most `size` px; null if missing. */
    static Bitmap decode(String path, int size) {
        try {
            if (path == null || path.isEmpty()) return null;
            if (path.startsWith("file://")) path = path.substring(7);
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / size); o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return null;
            int m = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - m) / 2, (b.getHeight() - m) / 2, m, m);
            return m > size ? Bitmap.createScaledBitmap(sq, size, size, true) : sq;
        } catch (Throwable e) { return null; }
    }

    static Bitmap rounded(Bitmap sq, int px, float r) {
        Bitmap s = Bitmap.createScaledBitmap(sq, px, px, true), out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(s, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(0, 0, px, px, r, r, p);
        return out;
    }

    static PendingIntent svc(Context c, String act, int code) {
        Intent i = new Intent(c, MediaService.class).setAction(act);
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, code, i, f) : PendingIntent.getService(c, code, i, f);
    }

    // ---------- Now playing ----------
    public static class Player extends AirWidget {
        int layout() { return R.layout.widget_player; }
        void bind(Context c, RemoteViews v, boolean dark) {
            SharedPreferences s = sp(c);
            String title = s.getString("np_title", "Air.INC"), artist = s.getString("np_artist", "Tap to open");
            boolean pl = s.getBoolean("np_playing", false);
            long dur = s.getLong("np_dur", 0), pos = s.getLong("np_pos", 0);
            if (pl) pos += System.currentTimeMillis() - s.getLong("np_ts", 0);
            int prog = dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : 0;
            int fg = dark ? 0xFFF2F2F4 : 0xFF111111, sub = dark ? 0xFF8E8E96 : 0xFF8A8A90;
            v.setTextViewText(R.id.wTitle, title.isEmpty() ? "Air.INC" : title);
            v.setTextViewText(R.id.wArtist, artist.isEmpty() ? "Unknown artist" : artist);
            v.setTextColor(R.id.wTitle, fg); v.setTextColor(R.id.wArtist, sub);
            for (int id : new int[]{R.id.wPrev, R.id.wPlay, R.id.wNext}) v.setInt(id, "setColorFilter", fg);
            Bitmap a = decode(s.getString("np_art", ""), 256);
            if (a != null) v.setImageViewBitmap(R.id.wArt, rounded(a, 256, 56));
            else v.setImageViewResource(R.id.wArt, R.drawable.w_art_ph);
            v.setImageViewResource(R.id.wPlay, pl ? R.drawable.ic_pause : R.drawable.ic_play);
            v.setViewVisibility(R.id.wProgL, dark ? 8 : 0); v.setViewVisibility(R.id.wProgD, dark ? 0 : 8);
            v.setProgressBar(R.id.wProgL, 1000, prog, false); v.setProgressBar(R.id.wProgD, 1000, prog, false);
            v.setOnClickPendingIntent(R.id.wPrev, svc(c, "prev", 11));
            v.setOnClickPendingIntent(R.id.wPlay, svc(c, "toggle", 12));
            v.setOnClickPendingIntent(R.id.wNext, svc(c, "next", 13));
        }
    }

    // ---------- Listening analytics ----------
    public static class Stats extends AirWidget {
        int layout() { return R.layout.widget_stats; }
        void bind(Context c, RemoteViews v, boolean dark) {
            JSONObject d = MediaService.readStats(sp(c));
            JSONArray w = d.optJSONArray("w");
            int fg = dark ? 0xFFF2F2F4 : 0xFF111111, sub = dark ? 0xFF8E8E96 : 0xFF8A8A90;
            v.setTextViewText(R.id.wBig, "" + d.optInt("p"));
            v.setTextViewText(R.id.wSub, "plays · " + Math.round(d.optLong("ms") / 60000f) + " min");
            v.setTextColor(R.id.wLbl, sub); v.setTextColor(R.id.wBig, fg); v.setTextColor(R.id.wSub, sub);
            int[] a = new int[7]; int mx = 1;
            for (int i = 0; i < 7; i++) { a[i] = w == null ? 0 : w.optInt(i); mx = Math.max(mx, a[i]); }
            Bitmap b = Bitmap.createBitmap(420, 110, Bitmap.Config.ARGB_8888); Canvas k = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 7; i++) {
                float h = 18 + a[i] * 92f / mx, x = i * 60;
                p.setColor(a[i] == mx && a[i] > 0 ? ORANGE : (dark ? 0xFF3A3A40 : 0xFFE4E4E8));
                k.drawRoundRect(x, 110 - h, x + 40, 110, 20, 20, p);
            }
            v.setImageViewBitmap(R.id.wBars, b);
        }
    }
}
