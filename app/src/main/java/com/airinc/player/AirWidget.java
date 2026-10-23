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
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Random;

/** Three 2x2 widgets (28dp corners): Now playing, Listening, Alarm. Drawn at the exact widget size so nothing is stretched. */
public abstract class AirWidget extends AppWidgetProvider {
    static final float RAD = 28f;
    static final int ORANGE = 0xFFFF6A45;
    abstract int layout();
    abstract void paint(Context c, Canvas k, float w, float h, boolean dark);
    void hits(Context c, RemoteViews v) { }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) draw(c, m, id); }
    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { draw(c, m, id); }

    void draw(Context c, AppWidgetManager m, int id) {
        Bundle o = m.getAppWidgetOptions(id);
        boolean land = c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        float w = Math.max(110, o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150));
        float h = Math.max(110, o.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150));
        float sc = Math.min(c.getResources().getDisplayMetrics().density, 560f / Math.max(w, h));
        Bitmap b = Bitmap.createBitmap(Math.round(w * sc), Math.round(h * sc), Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(b); k.scale(sc, sc);
        paint(c, k, w, h, dark(c));
        RemoteViews v = new RemoteViews(c.getPackageName(), layout());
        v.setImageViewBitmap(R.id.img, b);
        v.setOnClickPendingIntent(R.id.root, open(c, "music"));
        hits(c, v);
        m.updateAppWidget(id, v);
    }

    public static void refreshAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (Class<?> k : new Class<?>[]{Player.class, Stats.class, Alarm.class}) {
            int[] ids = m.getAppWidgetIds(new ComponentName(c, k));
            if (ids.length > 0) c.sendBroadcast(new Intent(c, k).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        }
    }

    // ---------- helpers ----------
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("air", 0); }

    static boolean dark(Context c) {
        String m = sp(c).getString("theme", "auto");
        if (m.equals("dark")) return true;
        if (m.equals("light")) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static PendingIntent open(Context c, String go) {
        return PendingIntent.getActivity(c, go.hashCode(), new Intent(c, MainActivity.class).putExtra("go", go)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent svc(Context c, String act, int code) {
        Intent i = new Intent(c, MediaService.class).setAction(act);
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, code, i, f) : PendingIntent.getService(c, code, i, f);
    }

    static Paint P(int col) { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(col); return p; }
    static TextPaint T(int col, float size, Typeface f) { TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG); p.setColor(col); p.setTextSize(size); p.setTypeface(f); return p; }
    static String fit(String s, TextPaint p, float w) { return TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString(); }

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

    static void circle(Canvas k, Bitmap art, float cx, float cy, float r) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix mt = new Matrix(); float s = 2 * r / art.getWidth(); mt.setScale(s, s); mt.postTranslate(cx - r, cy - r); sh.setLocalMatrix(mt);
        p.setShader(sh); k.drawCircle(cx, cy, r, p);
    }

    static void tri(Canvas k, Paint p, float x, float y, float s, boolean right) {
        Path t = new Path(); float d = right ? 1 : -1;
        t.moveTo(x - d * s / 2, y - s / 2); t.lineTo(x + d * s / 2, y); t.lineTo(x - d * s / 2, y + s / 2); t.close(); k.drawPath(t, p);
    }

    static final String[] F = {
        "01110 10001 10011 10101 11001 10001 01110", "00100 01100 00100 00100 00100 00100 01110",
        "01110 10001 00001 00010 00100 01000 11111", "11110 00001 00001 01110 00001 00001 11110",
        "00010 00110 01010 10010 11111 00010 00010", "11111 10000 11110 00001 00001 10001 01110",
        "00110 01000 10000 11110 10001 10001 01110", "11111 00001 00010 00100 01000 01000 01000",
        "01110 10001 10001 01110 10001 10001 01110", "01110 10001 10001 01111 00001 00010 01100", "0 0 1 0 1 0 0"};

    /** Dot-matrix digits like the "83" in the reference, right-aligned. */
    static void dots(Canvas k, String s, float right, float bottom, float step, int color) {
        Paint p = P(color); int cols = -1;
        for (char ch : s.toCharArray()) cols += (ch == ':' ? 1 : 5) + 1;
        float x = right - cols * step;
        for (char ch : s.toCharArray()) {
            String[] rows = F[ch == ':' ? 10 : Math.max(0, Math.min(9, ch - '0'))].split(" ");
            int wd = ch == ':' ? 1 : 5;
            for (int r = 0; r < 7; r++) for (int cc = 0; cc < wd; cc++)
                if (rows[r].charAt(cc) == '1') k.drawCircle(x + cc * step + step / 2, bottom - 7 * step + r * step + step / 2, step * .36f, p);
            x += (wd + 1) * step;
        }
    }

    /** Soft pink/green motion-blur backdrop (drawn tiny then upscaled = smooth blur). */
    static void aura(Canvas k, float w, float h, boolean dark, long seed) {
        int sw = Math.max(12, (int) (w / 9)), sh = Math.max(12, (int) (h / 9));
        Bitmap sm = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); Canvas c2 = new Canvas(sm);
        c2.drawColor(dark ? 0xFF2A2528 : 0xFFE9DEDD);
        int[] pal = dark ? new int[]{0xFF5A3A44, 0xFF3F4A34, 0xFF6A4A55, 0xFF2F2F34} : new int[]{0xFFE8B8C2, 0xFFB8C48C, 0xFFF4E6E4, 0xFFD9A8B6, 0xFFFFFFFF};
        Random r = new Random(seed); Paint p = P(0); p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 18; i++) {
            p.setColor(pal[r.nextInt(pal.length)]); p.setStrokeWidth(sh * (.05f + r.nextFloat() * .2f));
            float y = r.nextFloat() * sh, x0 = -sw * .2f + r.nextFloat() * sw * .5f;
            c2.drawLine(x0, y, x0 + sw * (.4f + r.nextFloat() * .8f), y + (r.nextFloat() - .5f) * sh * .12f, p);
        }
        k.drawBitmap(sm, null, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    /** Shared layout of the Listening and Alarm cards: translucent pill on top, dark pill button at the bottom. */
    static void card(Canvas k, float w, float h, boolean dark, long seed, String left, String right, String digits, String button) {
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, h), RAD, RAD, Path.Direction.CW);
        k.save(); k.clipPath(clip); aura(k, w, h, dark, seed); k.restore();
        int fg = dark ? 0xFFF2F2F2 : 0xFF222222, sub = dark ? 0xFFB5B5BA : 0xFF7A7A7A;
        float ph = Math.min(72, h - 70), top = 10;
        k.drawRoundRect(10, top, w - 10, top + ph, Math.min(RAD, ph / 2), Math.min(RAD, ph / 2), P(dark ? 0x66000000 : 0x99FFFFFF));
        k.drawText(left, 22, top + 18, T(fg, 10.5f, Typeface.MONOSPACE));
        TextPaint rp = T(sub, 10, Typeface.MONOSPACE); rp.setTextAlign(Paint.Align.RIGHT); k.drawText(right, w - 22, top + 18, rp);
        int cols = -1; for (char ch : digits.toCharArray()) cols += (ch == ':' ? 1 : 5) + 1;
        float step = Math.min(5.6f, Math.min((ph - 30) / 7f, (w - 44) / cols));
        dots(k, digits, w - 22, top + ph - 10, step, fg);
        float by = h - 46;
        k.drawRoundRect(10, by, w - 10, h - 10, 18, 18, P(dark ? 0xFFF2F2F2 : 0xFF141414));
        TextPaint bp = T(dark ? 0xFF141414 : 0xFFFFFFFF, 12, Typeface.MONOSPACE); bp.setTextAlign(Paint.Align.CENTER);
        k.drawText(button, w / 2, by + 22.5f, bp);
    }

    // ---------- 1. Now playing (like "Chill Session") ----------
    public static class Player extends AirWidget {
        int layout() { return R.layout.widget_player; }
        void hits(Context c, RemoteViews v) {
            v.setOnClickPendingIntent(R.id.bPrev, svc(c, "prev", 11));
            v.setOnClickPendingIntent(R.id.bPlay, svc(c, "toggle", 12));
            v.setOnClickPendingIntent(R.id.bNext, svc(c, "next", 13));
        }
        void paint(Context c, Canvas k, float w, float h, boolean dark) {
            SharedPreferences s = sp(c);
            int bg = dark ? 0xFF1C1C1F : 0xFFF4F4F4, fg = dark ? 0xFFF2F2F2 : 0xFF1A1A1A, sub = dark ? 0xFF8D8D93 : 0xFFA3A3A3;
            k.drawRoundRect(0, 0, w, h, RAD, RAD, P(bg));
            boolean pl = s.getBoolean("np_playing", false);
            String title = s.getString("np_title", ""), artist = s.getString("np_artist", "");
            if (title.isEmpty()) title = "Air.INC";
            if (artist.isEmpty()) artist = pl ? "Now playing" : "Next";
            float pad = 16, maxW = w - pad * 2 - 40;
            TextPaint tp = T(fg, 13, Typeface.MONOSPACE), ap = T(sub, 10.5f, Typeface.MONOSPACE);
            k.drawText(fit(title, tp, maxW), pad, pad + 11, tp);
            k.drawText(fit(artist, ap, maxW), pad, pad + 27, ap);
            Bitmap art = decode(s.getString("np_art", ""), 160);
            float ax = w - pad - 15, ay = pad + 15;
            if (art != null) circle(k, art, ax, ay, 15);
            else { Paint g = P(0); g.setShader(new RadialGradient(ax, ay, 15, 0xFFDCD9C8, ORANGE, Shader.TileMode.CLAMP)); k.drawCircle(ax, ay, 15, g); }
            // concentric-ring disc, tinted by the cover
            float cx = w / 2, cy = h / 2 + 8, r = Math.min(w, h) * .27f;
            float[] hsv = new float[3];
            int base = 0xFF9DAA73;
            if (art != null) { try { base = Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0); } catch (Exception e) { } }
            Color.colorToHSV(base, hsv); hsv[1] = Math.min(.5f, hsv[1]);
            for (int i = 0; i < 6; i++) { hsv[2] = Math.max(.22f, .8f - i * .08f); k.drawCircle(cx, cy, r * (1 - i * .15f), P(Color.HSVToColor(hsv))); }
            k.drawCircle(cx, cy, r * .1f, P(dark ? 0xFF15170F : 0xFF2B3320));
            long dur = s.getLong("np_dur", 0), pos = s.getLong("np_pos", 0);
            if (pl) pos += System.currentTimeMillis() - s.getLong("np_ts", 0);
            float f = dur > 0 ? Math.min(1f, pos / (float) dur) : 0, rr = r + 7;
            Paint st = P(0x33888888); st.setStyle(Paint.Style.STROKE); st.setStrokeWidth(2);
            RectF oval = new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
            k.drawArc(oval, 0, 360, false, st); st.setColor(ORANGE); st.setStrokeCap(Paint.Cap.ROUND); k.drawArc(oval, -90, 360 * f, false, st);
            // controls
            Paint gl = P(sub);
            float by = h - pad - 17;
            tri(k, gl, pad + 17, by, 11, false); k.drawRect(pad + 9, by - 5.5f, pad + 10.5f, by + 5.5f, gl);
            tri(k, gl, pad + 53, by, 11, true); k.drawRect(pad + 59.5f, by - 5.5f, pad + 61, by + 5.5f, gl);
            float px = w - pad - 17;
            k.drawCircle(px, by, 17, P(dark ? 0xFF4A4A50 : 0xFF9A9A9A));
            Paint wp = P(0xFFFFFFFF);
            if (pl) { k.drawRoundRect(px - 5, by - 6, px - 1.5f, by + 6, 1, 1, wp); k.drawRoundRect(px + 1.5f, by - 6, px + 5, by + 6, 1, 1, wp); }
            else tri(k, wp, px + 1, by, 12, true);
        }
    }

    // ---------- 2. Listening (like "Your aura 83") ----------
    public static class Stats extends AirWidget {
        int layout() { return R.layout.widget_stats; }
        void paint(Context c, Canvas k, float w, float h, boolean dark) {
            JSONObject d = MediaService.readStats(sp(c));
            int p = d.optInt("p");
            card(k, w, h, dark, 7, "Your plays", Math.round(d.optLong("ms") / 60000f) + " min", p > 9999 ? "9999" : "" + p, "Explore");
        }
    }

    // ---------- 3. Alarm ----------
    public static class Alarm extends AirWidget {
        static final String TOGGLE = "com.airinc.ALARM_TOGGLE";
        int layout() { return R.layout.widget_alarm; }
        @Override public void onReceive(Context c, Intent i) {
            if (TOGGLE.equals(i.getAction())) {
                SharedPreferences s = sp(c);
                Alarms.schedule(c, s.getInt("alarm_h", 10), s.getInt("alarm_m", 15), !s.getBoolean("alarm_on", false));
                refreshAll(c);
                return;
            }
            super.onReceive(c, i);
        }
        void hits(Context c, RemoteViews v) {
            v.setOnClickPendingIntent(R.id.bTime, open(c, "alarm"));
            v.setOnClickPendingIntent(R.id.bToggle, PendingIntent.getBroadcast(c, 31, new Intent(c, Alarm.class).setAction(TOGGLE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        void paint(Context c, Canvas k, float w, float h, boolean dark) {
            SharedPreferences s = sp(c);
            boolean on = s.getBoolean("alarm_on", false);
            String t = String.format("%02d:%02d", s.getInt("alarm_h", 10), s.getInt("alarm_m", 15));
            card(k, w, h, dark, 21, "Alarm", on ? "on" : "off", t, on ? "Turn off" : "Turn on");
        }
    }
}
