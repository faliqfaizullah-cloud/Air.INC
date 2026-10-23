package com.airinc.player;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;

/** Stores "memories" (photo / video / voice / text) privately inside the app and keeps a JSON index. */
public class Memories {
    static File dir(Context c) { File d = new File(c.getFilesDir(), "memories"); d.mkdirs(); return d; }
    static File cap(Context c) { File d = new File(c.getCacheDir(), "cap"); d.mkdirs(); return d; }
    static File idx(Context c) { return new File(dir(c), "index.json"); }

    static synchronized JSONArray load(Context c) {
        try (FileInputStream in = new FileInputStream(idx(c))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new JSONArray(o.toString("UTF-8"));
        } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void save(Context c, JSONArray a) {
        try (FileOutputStream out = new FileOutputStream(idx(c))) { out.write(a.toString().getBytes("UTF-8")); } catch (Exception e) { }
    }

    /** list for the web UI: files become absolute paths */
    static String list(Context c) {
        JSONArray a = load(c), r = new JSONArray();
        try {
            for (int i = a.length() - 1; i >= 0; i--) {   // newest first
                JSONObject o = new JSONObject(a.getJSONObject(i).toString());
                String d = dir(c).getAbsolutePath();
                if (o.has("file")) o.put("path", d + "/" + o.getString("file"));
                if (o.has("thumb")) o.put("tpath", d + "/" + o.getString("thumb"));
                r.put(o);
            }
        } catch (Exception e) { }
        return r.toString();
    }

    static JSONObject base(String type) throws Exception {
        long t = System.currentTimeMillis();
        return new JSONObject().put("id", "m" + t).put("type", type).put("ts", t).put("fav", false).put("title", "").put("text", "");
    }

    static String add(Context c, JSONObject o) {
        JSONArray a = load(c); a.put(o); save(c, a);
        return o.optString("id");
    }

    static String addText(Context c, String title, String text) {
        try { JSONObject o = base("text"); o.put("title", title).put("text", text); return add(c, o); } catch (Exception e) { return null; }
    }

    static boolean update(Context c, String id, String title, String text, Boolean fav) {
        JSONArray a = load(c);
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (id.equals(o.optString("id"))) {
                    if (title != null) o.put("title", title);
                    if (text != null) o.put("text", text);
                    if (fav != null) o.put("fav", fav.booleanValue());
                    save(c, a); return true;
                }
            }
        } catch (Exception e) { }
        return false;
    }

    static void delete(Context c, String id) {
        JSONArray a = load(c), n = new JSONArray();
        try {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (id.equals(o.optString("id"))) {
                    if (o.has("file")) new File(dir(c), o.getString("file")).delete();
                    if (o.has("thumb")) new File(dir(c), o.getString("thumb")).delete();
                } else n.put(o);
            }
        } catch (Exception e) { }
        save(c, n);
    }

    static File find(Context c, String id) {
        JSONArray a = load(c);
        for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null && id.equals(o.optString("id")) && o.has("file")) return new File(dir(c), o.optString("file")); }
        return null;
    }

    // ---------- photo ----------
    static Bitmap decode(File f, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int s = 1; while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) return null;
        try {
            int rot = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            int deg = rot == 6 ? 90 : rot == 3 ? 180 : rot == 8 ? 270 : 0;
            if (deg != 0) { Matrix m = new Matrix(); m.postRotate(deg); b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); }
        } catch (Exception e) { }
        float k = Math.min(1f, (float) max / Math.max(b.getWidth(), b.getHeight()));
        if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
        return b;
    }

    static void jpg(Bitmap b, File f, int q) throws IOException { try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, q, o); } }

    /** import a captured / picked image file as a memory */
    static String addPhoto(Context c, File src) {
        try {
            JSONObject o = base("photo"); String id = o.getString("id");
            Bitmap big = decode(src, 1600); if (big == null) return null;
            jpg(big, new File(dir(c), id + ".jpg"), 88);
            Bitmap th = decode(src, 420); if (th != null) jpg(th, new File(dir(c), id + "_t.jpg"), 80);
            o.put("file", id + ".jpg").put("thumb", id + "_t.jpg").put("w", big.getWidth()).put("h", big.getHeight());
            return add(c, o);
        } catch (Exception e) { return null; } finally { src.delete(); }
    }

    static String addVideo(Context c, File src) {
        try {
            JSONObject o = base("video"); String id = o.getString("id");
            File dst = new File(dir(c), id + ".mp4");
            if (!src.renameTo(dst)) { copy(new FileInputStream(src), dst); src.delete(); }
            long dur = 0;
            try { MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(dst.getAbsolutePath()); dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); r.release(); } catch (Exception e) { }
            Bitmap th = ThumbnailUtils.createVideoThumbnail(dst.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
            if (th != null) {
                float k = Math.min(1f, 480f / Math.max(th.getWidth(), th.getHeight()));
                if (k < 1f) th = Bitmap.createScaledBitmap(th, Math.round(th.getWidth() * k), Math.round(th.getHeight() * k), true);
                jpg(th, new File(dir(c), id + "_t.jpg"), 82); o.put("thumb", id + "_t.jpg");
            }
            o.put("file", id + ".mp4").put("dur", dur);
            return add(c, o);
        } catch (Exception e) { return null; }
    }

    static String addVoice(Context c, File src, long dur) {
        try {
            JSONObject o = base("voice"); String id = o.getString("id");
            File dst = new File(dir(c), id + ".m4a");
            if (!src.renameTo(dst)) { copy(new FileInputStream(src), dst); src.delete(); }
            o.put("file", id + ".m4a").put("dur", dur);
            return add(c, o);
        } catch (Exception e) { return null; }
    }

    static File copy(InputStream in, File dst) throws IOException {
        try (InputStream i = in; OutputStream o = new FileOutputStream(dst)) { byte[] b = new byte[16384]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); }
        return dst;
    }

    /** a picked content:// uri (gallery) */
    static String addPicked(Context c, Uri u) {
        try {
            ContentResolver cr = c.getContentResolver();
            String t = cr.getType(u); if (t == null) t = "";
            boolean vid = t.startsWith("video/");
            File tmp = copy(cr.openInputStream(u), new File(cap(c), "pick_" + System.currentTimeMillis()));
            return vid ? addVideo(c, tmp) : addPhoto(c, tmp);
        } catch (Exception e) { return null; }
    }
}
