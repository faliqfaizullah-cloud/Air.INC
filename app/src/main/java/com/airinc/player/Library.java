package com.airinc.player;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.HashMap;

/** Scans MediaStore for music, extracts album art, and saves the library to disk. */
public class Library {
    public interface Cb { void on(JSONArray lib, boolean done); }
    static volatile boolean scanning;

    static File file(Context c) { return new File(c.getFilesDir(), "library.json"); }

    public static JSONArray load(Context c) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(file(c)));
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return new JSONArray(sb.toString());
        } catch (Exception e) { return new JSONArray(); }
    }

    static void save(Context c, JSONArray a) {
        try { FileWriter w = new FileWriter(file(c)); w.write(a.toString()); w.close(); } catch (Exception e) { }
    }

    static File artFile(Context c, String key) {
        File d = new File(c.getFilesDir(), "art"); d.mkdirs();
        return new File(d, "a" + key + ".jpg");
    }

    /** Embedded cover for a track, written as a 512px JPEG. Returns path or "". */
    static String makeArt(Context c, long id, File out) {
        Bitmap b = null;
        Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        if (Build.VERSION.SDK_INT >= 29) {
            try { b = c.getContentResolver().loadThumbnail(u, new Size(512, 512), null); } catch (Exception e) { }
        }
        if (b == null) {
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(c, u);
                byte[] p = r.getEmbeddedPicture();
                r.release();
                if (p != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(p, 0, p.length, o);
                    o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / 512); o.inJustDecodeBounds = false;
                    b = BitmapFactory.decodeByteArray(p, 0, p.length, o);
                }
            } catch (Exception e) { }
        }
        if (b == null) return "";
        try {
            FileOutputStream fo = new FileOutputStream(out);
            b.compress(Bitmap.CompressFormat.JPEG, 90, fo); fo.close();
            return out.getAbsolutePath();
        } catch (Exception e) { return ""; }
    }

    public static void scan(final Context ctx, final Cb cb) {
        if (scanning) return;
        scanning = true;
        new Thread(() -> {
            try {
                JSONArray out = new JSONArray();
                Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    Build.VERSION.SDK_INT >= 30
                        ? new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.AudioColumns.GENRE}
                        : new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION},
                    MediaStore.Audio.Media.IS_MUSIC + "!=0 AND " + MediaStore.Audio.Media.DURATION + ">=20000", null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        long id = c.getLong(0), aid = c.getLong(4);
                        String artist = c.getString(2);
                        o.put("id", id); o.put("title", c.getString(1));
                        o.put("artist", artist == null || artist.equals("<unknown>") ? "" : artist);
                        o.put("album", c.getString(3)); o.put("aid", aid); o.put("dur", c.getLong(5));
                        if (Build.VERSION.SDK_INT >= 30) { String gn = c.getString(6); o.put("genre", gn == null ? "" : gn.trim()); }
                        File f = artFile(ctx, aid > 0 ? "al" + aid : "tr" + id);
                        o.put("art", f.exists() ? f.getAbsolutePath() : "");
                        out.put(o);
                    }
                    c.close();
                }
                save(ctx, out);
                cb.on(out, false);
                // Phase 2: extract missing covers once per album
                HashMap<String, String> done = new HashMap<>();
                int found = 0;
                for (int i = 0; i < out.length(); i++) {
                    JSONObject o = out.getJSONObject(i);
                    if (o.optString("art").length() > 0) continue;
                    long aid = o.optLong("aid"), id = o.optLong("id");
                    String key = aid > 0 ? "al" + aid : "tr" + id;
                    String p = done.get(key);
                    if (p == null) { p = makeArt(ctx, id, artFile(ctx, key)); done.put(key, p); }
                    if (p.length() > 0) { o.put("art", p); if (++found % 60 == 0) { save(ctx, out); cb.on(out, false); } }
                }
                save(ctx, out);
                cb.on(out, true);
            } catch (Exception e) { /* ignore */ }
            scanning = false;
        }).start();
    }
}
