package com.airinc.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.*;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;

/** Home-screen widgets in the Air OS look: orb clock, listening profile, now playing, listening level. */
public abstract class AirWidget extends AppWidgetProvider {
    static final int ORANGE = 0xFFFF5A3C, SALMON = 0xFFFF8F72, CREAM = 0xFFDCD9C8;
    abstract int layout();
    abstract void bind(Context c, RemoteViews v);

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), layout());
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            bind(c, v);
            m.updateAppWidget(id, v);
        }
    }

    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<?> k : new Class<?>[]{Orb.class, Profile.class, Player.class, Level.class}) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
            if (ids.length > 0) c.sendBroadcast(new Intent(c, k).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        }
    }

    // ---------- helpers ----------
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("air", 0); }
    static JSONObject data(Context c) { try { return new JSONObject(sp(c).getString("json", "{}")); } catch (Exception e) { return new JSONObject(); } }
    static Paint P(int col) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(col); return p; }
    static TextPaint T(int col, float size, Typeface f) { TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG); p.setColor(col); p.setTextSize(size); p.setTypeface(f); return p; }
    static Shader orb(float cx, float cy, float r) {
        return new RadialGradient(cx, cy, r, new int[]{CREAM, CREAM, SALMON, ORANGE}, new float[]{0, .4f, .8f, 1}, Shader.TileMode.CLAMP);
    }
    /** Decodes an album-art file into a centre-cropped square of at most `size` px; null if missing. */
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
    static String fm(long ms) { long s = Math.max(0, ms / 1000); return String.format("%02d:%02d", s / 60, s % 60); }
    static void tri(Canvas c, Paint p, float x, float y, float s, boolean right) {
        Path t = new Path(); float d = right ? 1 : -1;
        t.moveTo(x - d * s / 2, y - s / 2); t.lineTo(x + d * s / 2, y); t.lineTo(x - d * s / 2, y + s / 2); t.close(); c.drawPath(t, p);
    }
    static PendingIntent svc(Context c, String act, int code) {
        Intent i = new Intent(c, MediaService.class).setAction(act);
        return MediaService.running ? PendingIntent.getService(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
            : PendingIntent.getActivity(c, code, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- 1. Orb clock (2x2) ----------
    public static class Orb extends AirWidget {
        int layout() { return R.layout.widget_orb; }
        void bind(Context c, RemoteViews v) {
            Bitmap b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888); Canvas k = new Canvas(b);
            k.drawRoundRect(0, 0, 400, 400, 110, 110, P(0xFF000000));
            Paint o = P(0xFFFFFFFF); o.setShader(orb(200, 200, 150)); k.drawCircle(200, 200, 150, o);
            Paint t = P(0xFFFFFFFF); t.setStrokeWidth(4);
            k.drawLine(200, 14, 200, 38, t); k.drawLine(200, 362, 200, 386, t); k.drawLine(14, 200, 38, 200, t); k.drawLine(362, 200, 386, 200, t);
            v.setImageViewBitmap(R.id.img, b);
        }
    }

    // ---------- 2. Listening profile (4x2) ----------
    public static class Profile extends AirWidget {
        int layout() { return R.layout.widget_img; }
        void bind(Context c, RemoteViews v) {
            JSONObject d = data(c); int p = d.optInt("p"), s = d.optInt("s"); JSONArray w = d.optJSONArray("w");
            int pct = p > 0 ? (p - s) * 100 / p : 0; int mx = 1;
            int[] a = new int[7]; for (int i = 0; i < 7; i++) { a[i] = w == null ? 0 : w.optInt(i); mx = Math.max(mx, a[i]); }
            Bitmap b = Bitmap.createBitmap(800, 360, Bitmap.Config.ARGB_8888); Canvas k = new Canvas(b);
            k.drawRoundRect(0, 0, 800, 360, 70, 70, P(0xFFFFFFFF));
            k.drawRect(48, 52, 52, 118, P(0xFF111111));
            k.drawText("Adaptive", 76, 82, T(0xFF111111, 34, Typeface.DEFAULT));
            k.drawText("Listening Profile", 76, 122, T(0xFF8A8A8E, 34, Typeface.DEFAULT));
            k.drawText(pct + "%", 48, 290, T(0xFF111111, 78, Typeface.create("sans-serif-light", Typeface.NORMAL)));
            k.drawRect(330, 208, 760, 210, P(0xFF222222));
            Paint grey = P(0xFFE6E6E8), or = P(0xFFFF6A45);
            for (int i = 0; i < 7; i++) {
                float x = 336 + i * 62, f = a[i] / (float) mx;
                float oh = 26 + f * 110, gh = 30 + (1 - f) * 90;
                or.setShader(new LinearGradient(0, 200 - oh, 0, 200, ORANGE, SALMON, Shader.TileMode.CLAMP));
                k.drawRoundRect(x, 200 - oh, x + 36, 200, 18, 18, or);
                k.drawRoundRect(x, 220, x + 36, 220 + gh, 18, 18, grey);
            }
            v.setImageViewBitmap(R.id.img, b);
        }
    }

    // ---------- 3. Now playing "Daily" (4x2) ----------
    public static class Player extends AirWidget {
        int layout() { return R.layout.widget_player; }
        void bind(Context c, RemoteViews v) {
            SharedPreferences s = sp(c);
            String title = s.getString("title", "Nothing playing"), artist = s.getString("artist", "Open Air.INC to start");
            boolean pl = s.getBoolean("playing", false); long dur = s.getLong("dur", 0), pos = s.getLong("pos", 0);
            if (pl) pos += System.currentTimeMillis() - s.getLong("ts", 0);
            float f = dur > 0 ? Math.min(1f, pos / (float) dur) : 0;
            Bitmap b = Bitmap.createBitmap(800, 360, Bitmap.Config.ARGB_8888); Canvas k = new Canvas(b);
            Paint bg = P(0xFF000000); bg.setShader(new LinearGradient(0, 0, 0, 360, new int[]{0xFF8E8A82, 0xFF9A8F88, 0xFFE28F77}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
            k.drawRoundRect(0, 0, 800, 360, 90, 90, bg);
            Paint o = P(0xFFFFFFFF); Bitmap art = decode(s.getString("art", ""), 124);
            if (art != null) {
                o.setShader(new BitmapShader(Bitmap.createScaledBitmap(art, 124, 124, true), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                k.translate(48, 48); k.drawCircle(62, 62, 62, o); k.translate(-48, -48);
            } else { o.setShader(orb(110, 110, 62)); k.drawCircle(110, 110, 62, o); k.drawCircle(150, 150, 12, P(0xFFFF4A2A)); }
            k.drawText("Daily", 220, 96, T(0xFFFFFFFF, 40, Typeface.DEFAULT));
            Paint chip = P(0x44FFFFFF); k.drawRoundRect(330, 62, 418, 104, 21, 21, chip);
            k.drawText(pl ? "play" : "pause", 342, 93, T(0xFFFFFFFF, 24, Typeface.DEFAULT));
            k.drawRect(220, 128, 750, 131, P(0x66FFFFFF)); k.drawRect(220, 128, 220 + 530 * f, 131, P(0xFFFFFFFF));
            k.drawText(fm(pos), 220, 168, T(0xAAFFFFFF, 24, Typeface.DEFAULT));
            TextPaint dp = T(0xAAFFFFFF, 24, Typeface.DEFAULT); dp.setTextAlign(Paint.Align.RIGHT); k.drawText(fm(dur), 750, 168, dp);
            TextPaint tp = T(0xFFFFFFFF, 38, Typeface.MONOSPACE);
            k.drawText(TextUtils.ellipsize(title, tp, 420, TextUtils.TruncateAt.END).toString(), 48, 262, tp);
            TextPaint ap = T(0xBBFFFFFF, 28, Typeface.MONOSPACE);
            k.drawText(TextUtils.ellipsize(artist, ap, 420, TextUtils.TruncateAt.END).toString(), 48, 312, ap);
            Paint w = P(0xFFFFFFFF);
            tri(k, w, 568, 290, 34, false); k.drawRect(584, 273, 590, 307, w); k.drawRect(546, 273, 552, 307, w);
            if (pl) { k.drawRect(630, 270, 642, 310, w); k.drawRect(658, 270, 670, 310, w); } else tri(k, w, 652, 290, 40, true);
            tri(k, w, 736, 290, 34, true); k.drawRect(716, 273, 722, 307, w);
            v.setImageViewBitmap(R.id.img, b);
            v.setOnClickPendingIntent(R.id.bPrev, svc(c, "prev", 11));
            v.setOnClickPendingIntent(R.id.bPlay, svc(c, "toggle", 12));
            v.setOnClickPendingIntent(R.id.bNext, svc(c, "next", 13));
        }
    }

    // ---------- 4. Listening level pill (2x3) ----------
    public static class Level extends AirWidget {
        int layout() { return R.layout.widget_img; }
        void bind(Context c, RemoteViews v) {
            JSONObject d = data(c); JSONArray w = d.optJSONArray("w");
            int today = w == null ? 0 : w.optInt(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1);
            String lvl = today >= 5 ? "High" : today >= 2 ? "Mid" : "Low";
            Bitmap b = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888); Canvas k = new Canvas(b);
            Paint bg = P(0xFFFFFFFF);
            bg.setShader(new RadialGradient(200, 380, 330, new int[]{0xFFFF4A2A, SALMON, CREAM}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            k.drawRoundRect(0, 0, 400, 600, 200, 200, bg);
            TextPaint t = T(0xFFFFFFFF, 32, Typeface.DEFAULT); t.setTextAlign(Paint.Align.CENTER);
            k.drawText("Listening level", 200, 112, t);
            k.drawRoundRect(268, 190, 352, 236, 23, 23, P(0x55FFFFFF));
            TextPaint n = T(0xFFFFFFFF, 26, Typeface.DEFAULT); n.setTextAlign(Paint.Align.CENTER); k.drawText("" + d.optInt("p"), 310, 222, n);
            Paint wv = P(0xAAFFE9DC); wv.setStyle(Paint.Style.STROKE); wv.setStrokeWidth(9); wv.setStrokeCap(Paint.Cap.ROUND);
            Path p = new Path(); p.moveTo(90, 450); p.cubicTo(120, 380, 160, 380, 185, 470); p.cubicTo(205, 540, 240, 450, 255, 380); p.cubicTo(270, 320, 290, 340, 312, 450);
            k.drawPath(p, wv);
            k.drawRoundRect(140, 500, 260, 560, 30, 30, P(0x66705A50));
            k.drawText(lvl, 200, 540, t);
            v.setImageViewBitmap(R.id.img, b);
        }
    }
}
