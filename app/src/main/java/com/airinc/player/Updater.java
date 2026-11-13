package com.airinc.player;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-app updater: asks GitHub for the newest release, downloads its .apk, hands it to the system installer. */
public class Updater {
    public interface Out { void send(String json); }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean busy = new AtomicBoolean(false);
    private static volatile boolean cancel = false;

    public static File apk(Context c) { return new File(new File(c.getCacheDir(), "upd"), "Air.INC.apk"); }

    static int[] nums(String v) {
        String[] p = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] r = new int[4];
        for (int i = 0; i < 4 && i < p.length; i++) { try { r[i] = Integer.parseInt(p[i]); } catch (Exception e) { } }
        return r;
    }
    static boolean newer(String latest, String cur) {
        int[] a = nums(latest), b = nums(cur);
        for (int i = 0; i < 4; i++) { if (a[i] != b[i]) return a[i] > b[i]; }
        return false;
    }
    private static void post(Out o, JSONObject j) { final String s = j.toString(); UI.post(() -> o.send(s)); }

    public static void check(final Context c, final Out o) {
        new Thread(() -> {
            JSONObject j = new JSONObject();
            try {
                String cur = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
                j.put("t", "check").put("cur", cur);
                if (BuildConfig.REPO.isEmpty()) { j.put("err", "repo"); post(o, j); return; }
                HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + BuildConfig.REPO + "/releases/latest").openConnection();
                h.setConnectTimeout(10000); h.setReadTimeout(15000);
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "Air.INC");
                if (h.getResponseCode() != 200) { j.put("err", "http" + h.getResponseCode()); post(o, j); return; }
                StringBuilder sb = new StringBuilder();
                try (InputStream in = h.getInputStream()) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8")); }
                JSONObject r = new JSONObject(sb.toString());
                String tag = r.optString("tag_name", "");
                JSONArray as = r.optJSONArray("assets");
                String url = "", size = "0";
                if (as != null) for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (a.optString("name").toLowerCase().endsWith(".apk")) { url = a.optString("browser_download_url"); size = String.valueOf(a.optLong("size")); break; }
                }
                j.put("latest", tag.replaceFirst("^[vV]", "")).put("url", url).put("size", Long.parseLong(size))
                 .put("notes", r.optString("body", "")).put("newer", !url.isEmpty() && newer(tag, cur));
            } catch (Exception e) { try { j.put("err", "net"); } catch (Exception x) { } }
            post(o, j);
        }).start();
    }

    public static void download(final Context c, final String url, final Out o) {
        if (!url.startsWith("https://") || !busy.compareAndSet(false, true)) return;
        cancel = false;
        new Thread(() -> {
            JSONObject j = new JSONObject();
            File f = apk(c), tmp = new File(f.getParentFile(), "dl.part");
            try {
                f.getParentFile().mkdirs();
                HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(30000);
                h.setRequestProperty("User-Agent", "Air.INC");
                long total = h.getContentLengthLong(), got = 0; int last = -1;
                if (h.getResponseCode() != 200) throw new Exception("http");
                try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[32768]; int n;
                    while ((n = in.read(b)) > 0) {
                        if (cancel) throw new Exception("cancel");
                        out.write(b, 0, n); got += n;
                        int p = total > 0 ? (int) (got * 100 / total) : 0;
                        if (p != last) { last = p; post(o, new JSONObject().put("t", "prog").put("pct", p)); }
                    }
                }
                // sanity check: must be our own app and a different build
                PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                if (pi == null || !c.getPackageName().equals(pi.packageName)) throw new Exception("bad");
                f.delete();
                if (!tmp.renameTo(f)) throw new Exception("io");
                j.put("t", "done");
            } catch (Exception e) {
                tmp.delete();
                try { j.put("t", "fail").put("why", String.valueOf(e.getMessage())); } catch (Exception x) { }
            }
            busy.set(false);
            post(o, j);
        }).start();
    }

    public static void cancel() { cancel = true; }
}
