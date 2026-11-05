package com.airinc.player;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Finds lyrics: cache -> .lrc/.txt next to the song -> LRCLIB (free, synced lyrics). */
public class Lyrics {
    static String read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    static String pathOf(Context c, long id) {
        try (Cursor q = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Audio.Media.DATA}, MediaStore.Audio.Media._ID + "=?", new String[]{String.valueOf(id)}, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Throwable t) { }
        return null;
    }

    static String http(String u) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(8000); h.setReadTimeout(9000);
        h.setRequestProperty("User-Agent", "Air.INC/2.2 (Android)");
        try {
            if (h.getResponseCode() != 200) return null;
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                return o.toString("UTF-8");
            }
        } finally { h.disconnect(); }
    }

    static String pack(String synced, String plain, String src) throws Exception {
        return new JSONObject().put("synced", synced == null ? "" : synced).put("plain", plain == null ? "" : plain).put("src", src).toString();
    }

    static String get(Context c, long id, String title, String artist, String album, int dur) {
        File dir = new File(c.getFilesDir(), "lyrics"); dir.mkdirs();
        File cache = new File(dir, id + ".json");
        try {
            if (cache.exists()) {
                String s = read(cache); JSONObject o = new JSONObject(s);
                if (!o.optBoolean("none") || System.currentTimeMillis() - o.optLong("ts") < 3L * 86400000L) return s;
            }
        } catch (Exception e) { }
        try {
            String p = pathOf(c, id);
            if (p != null) {
                String base = p.replaceAll("\\.[^./]+$", "");
                for (String ext : new String[]{".lrc", ".LRC", ".txt"}) {
                    File f = new File(base + ext);
                    if (f.exists() && f.length() > 0) {
                        String t = read(f);
                        String out = t.matches("(?s).*\\[\\d+:\\d+.*") ? pack(t, "", "file") : pack("", t, "file");
                        try (FileOutputStream w = new FileOutputStream(cache)) { w.write(out.getBytes("UTF-8")); }
                        return out;
                    }
                }
            }
        } catch (Exception e) { }
        try {
            String q = "track_name=" + URLEncoder.encode(title, "UTF-8") + "&artist_name=" + URLEncoder.encode(artist == null ? "" : artist, "UTF-8")
                + "&album_name=" + URLEncoder.encode(album == null ? "" : album, "UTF-8") + (dur > 0 ? "&duration=" + dur : "");
            String r = http("https://lrclib.net/api/get?" + q);
            JSONObject o = null;
            if (r != null) o = new JSONObject(r);
            if (o == null || (o.optString("syncedLyrics", "").isEmpty() && o.optString("plainLyrics", "").isEmpty())) {
                String s = http("https://lrclib.net/api/search?q=" + URLEncoder.encode(title + " " + (artist == null ? "" : artist), "UTF-8"));
                o = null;
                if (s != null) {
                    JSONArray a = new JSONArray(s);
                    for (int i = 0; i < a.length() && o == null; i++) {
                        JSONObject x = a.getJSONObject(i);
                        boolean ok = !x.optString("syncedLyrics", "").isEmpty() || !x.optString("plainLyrics", "").isEmpty();
                        if (ok && (dur <= 0 || Math.abs(x.optDouble("duration", dur) - dur) < 6)) o = x;
                    }
                }
            }
            String out;
            if (o == null) out = new JSONObject().put("none", true).put("ts", System.currentTimeMillis()).toString();
            else out = pack(o.optString("syncedLyrics", ""), o.optString("plainLyrics", ""), "lrclib");
            try (FileOutputStream w = new FileOutputStream(cache)) { w.write(out.getBytes("UTF-8")); }
            return out;
        } catch (Exception e) {
            return "{\"err\":true}";
        }
    }
}
