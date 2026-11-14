package com.airinc.player;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Air Link v1 (Android): same LAN protocol as the Windows app. Lets a paired PC control this phone, browse and stream its songs,
 *  and send songs to it. Keys are random 192-bit values; the on-screen code is only a short-lived pairing secret. */
public class LinkServer extends NanoHTTPD {
    public interface Host { void cmd(String a, JSONObject o); void pulled(String name); void changed(); }

    static final int[] PORTS = {38211, 38212, 38213, 38214, 38215};
    static final Set<String> CMDS = new HashSet<>(Arrays.asList("play", "toggle", "resume", "pause", "next", "prev", "seek", "vol", "shuf", "rep", "queue", "speed", "sleep", "kar", "key"));
    static final long MAX_PULL = 600L * 1024 * 1024;
    static final SecureRandom RND = new SecureRandom();

    static final Response.IStatus TOO_MANY = new Response.IStatus() {
        @Override public int getRequestStatus() { return 429; }
        @Override public String getDescription() { return "429 Too Many Requests"; }
    };
    static LinkServer inst;
    final Context ctx; volatile Host host; final SharedPreferences sp;
    String secret = digits(5); int fails; long lockUntil; volatile int libV = 1;
    final JSONObject keys; // peerId -> {key,name,platform}
    final Map<String, long[]> tokens = new ConcurrentHashMap<>(); // token -> {trackId, expiry}
    final Map<String, JSONObject> jobs = new ConcurrentHashMap<>();
    int port; String ip = "";

    private LinkServer(Context c, Host h, int p) {
        super("0.0.0.0", p);
        ctx = c.getApplicationContext(); host = h; port = p;
        sp = ctx.getSharedPreferences("airlink", Context.MODE_PRIVATE);
        JSONObject k; try { k = new JSONObject(sp.getString("keys", "{}")); } catch (Exception e) { k = new JSONObject(); }
        keys = k;
    }

    /** Start (once) on the first free port. Returns null when none is free. */
    public static synchronized LinkServer ensure(Context c, Host h) {
        if (inst != null && inst.isAlive()) { inst.host = h; inst.refreshIp(); return inst; }
        for (int p : PORTS) {
            try { LinkServer s = new LinkServer(c, h, p); s.start(SOCKET_READ_TIMEOUT, false); s.refreshIp(); inst = s; return s; } catch (Exception e) { }
        }
        return null;
    }
    public static boolean wanted(Context c) {
        SharedPreferences sp = c.getSharedPreferences("airlink", Context.MODE_PRIVATE);
        return !sp.getString("keys", "{}").equals("{}") || !sp.getString("out", "{}").equals("{}");
    }

    static String digits(int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(RND.nextInt(10)); return b.toString(); }
    static String hex(int n) { byte[] b = new byte[n]; RND.nextBytes(b); StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static boolean same(String a, String b) { return MessageDigest.isEqual(a.getBytes(), b.getBytes()); }
    static boolean isPrivate(String ip) { return ip.matches("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.).*") || ip.equals("127.0.0.1"); }

    // ---------- own address ----------
    String refreshIp() {
        String best = ""; int bestRank = 99;
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                String nm = n.getName().toLowerCase();
                if (nm.startsWith("rmnet") || nm.startsWith("tun") || nm.startsWith("ppp") || nm.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address)) continue;
                    String h = a.getHostAddress(); if (!isPrivate(h)) continue;
                    int r = (nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan") ? 0 : 3) + (h.startsWith("192.168.") ? 0 : h.startsWith("10.") ? 1 : 2);
                    if (r < bestRank) { bestRank = r; best = h; }
                }
            }
        } catch (Exception e) { }
        ip = best; return ip;
    }
    String code() {
        if (ip.isEmpty()) return "";
        String[] p = ip.split("\\.");
        int n = Integer.parseInt(p[2]) * 256 + Integer.parseInt(p[3]);
        return String.format("%05d", n) + " " + secret;
    }
    String qr() { return ip.isEmpty() ? "" : "airlink://" + ip + ":" + port + "/" + secret; }

    // ---------- state shared with the page ----------
    static String deviceName() { String m = Build.MODEL; return m == null || m.isEmpty() ? "Android" : m; }
    JSONObject outPeers() { try { return new JSONObject(sp.getString("out", "{}")); } catch (Exception e) { return new JSONObject(); } }
    void saveOut(JSONObject o) { sp.edit().putString("out", o.toString()).apply(); }
    void saveKeys() { sp.edit().putString("keys", keys.toString()).apply(); host.changed(); }

    public String info() {
        try {
            refreshIp();
            JSONObject o = new JSONObject();
            o.put("name", deviceName()); o.put("platform", "android");
            JSONArray out = new JSONArray(); JSONObject op = outPeers();
            for (Iterator<String> it = op.keys(); it.hasNext(); ) out.put(op.getJSONObject(it.next()));
            o.put("out", out); o.put("on", !ip.isEmpty()); o.put("ip", ip); o.put("port", port);
            o.put("code", code()); o.put("qr", qr()); o.put("secret", secret);
            JSONArray peers = new JSONArray();
            for (Iterator<String> it = keys.keys(); it.hasNext(); ) {
                String id = it.next(); JSONObject k = keys.getJSONObject(id);
                peers.put(new JSONObject().put("id", id).put("name", k.optString("name")).put("platform", k.optString("platform")));
            }
            o.put("peers", peers);
            return o.toString();
        } catch (Exception e) { return "{\"on\":false}"; }
    }
    public String newSecret() { secret = digits(5); fails = 0; return info(); }
    public void kick(String id) { keys.remove(id); saveKeys(); }
    public void addOut(String json) {
        try {
            JSONObject r = new JSONObject(json);
            String id = r.optString("id"), ip = r.optString("ip"), key = r.optString("key");
            if (id.isEmpty() || ip.isEmpty() || key.isEmpty()) return;
            JSONObject o = new JSONObject().put("id", id).put("name", r.optString("name", "Device")).put("platform", r.optString("platform"))
                .put("ip", ip).put("port", r.optInt("port")).put("key", key);
            JSONObject all = outPeers(); all.put(id, o); saveOut(all);
        } catch (Exception e) { }
    }
    public void delOut(String id) { JSONObject all = outPeers(); all.remove(id); saveOut(all); }
    public void bump() { libV++; }

    /** Builds one-off URLs the paired PC can pull a track from (valid 30 min). */
    public String mint(String id) {
        try {
            long tid = Long.parseLong(id); String t = hex(12);
            long now = System.currentTimeMillis(); tokens.put(t, new long[]{tid, now + 30 * 60000});
            for (Iterator<Map.Entry<String, long[]>> it = tokens.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue()[1] < now) it.remove();
            String base = "http://" + ip + ":" + port; String name = id + ".mp3";
            JSONArray lib = Library.load(ctx);
            for (int i = 0; i < lib.length(); i++) { JSONObject o = lib.getJSONObject(i); if (o.optLong("id") == tid) { name = fileName(o.optString("title"), tid); break; } }
            return new JSONObject().put("m", base + "/media/" + id + "?t=" + t).put("a", base + "/art/" + id + "?t=" + t).put("name", name).toString();
        } catch (Exception e) { return ""; }
    }
    String fileName(String title, long id) {
        String ext = ".mp3"; String mime = ctx.getContentResolver().getType(trackUri(id));
        if (mime != null) { if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) ext = ".m4a"; else if (mime.contains("flac")) ext = ".flac"; else if (mime.contains("wav")) ext = ".wav"; else if (mime.contains("ogg")) ext = ".ogg"; }
        String n = (title == null || title.isEmpty() ? "song" : title).replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_");
        return (n.length() > 100 ? n.substring(0, 100) : n) + ext;
    }
    static Uri trackUri(long id) { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }

    // ---------- HTTP ----------
    Response json(Response.IStatus st, JSONObject o) { return cors(newFixedLengthResponse(st, "application/json", o.toString())); }
    Response err(Response.IStatus st, String e) { try { return json(st, new JSONObject().put("err", e)); } catch (Exception x) { return cors(newFixedLengthResponse(st, "text/plain", e)); } }
    Response cors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*"); r.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Air-Key, Range");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS"); r.addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length");
        r.addHeader("Cache-Control", "no-store"); return r;
    }
    String who(IHTTPSession s) {
        String k = s.getHeaders().get("x-air-key"); if (k == null) { List<String> l = s.getParameters().get("k"); if (l != null && !l.isEmpty()) k = l.get(0); }
        if (k == null || k.isEmpty()) return null;
        for (Iterator<String> it = keys.keys(); it.hasNext(); ) { String id = it.next(); if (same(keys.optJSONObject(id).optString("key"), k)) return id; }
        return null;
    }
    boolean tokenOk(IHTTPSession s, long id) {
        List<String> l = s.getParameters().get("t"); if (l == null || l.isEmpty()) return false;
        long[] t = tokens.get(l.get(0)); return t != null && t[1] > System.currentTimeMillis() && t[0] == id;
    }
    JSONObject body(IHTTPSession s) {
        try {
            int n = Integer.parseInt(s.getHeaders().containsKey("content-length") ? s.getHeaders().get("content-length") : "0");
            if (n > 65536) return new JSONObject();
            Map<String, String> m = new HashMap<>(); s.parseBody(m);
            String b = m.get("postData"); return b == null || b.isEmpty() ? new JSONObject() : new JSONObject(b);
        } catch (Exception e) { return new JSONObject(); }
    }

    @Override public Response serve(IHTTPSession s) {
        try {
            String p = s.getUri(); Method m = s.getMethod();
            if (m == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""));
            if (p.equals("/info")) return json(Response.Status.OK, new JSONObject().put("app", "airinc").put("v", 1).put("name", deviceName()).put("platform", "android").put("ip", ip).put("port", port));
            if (p.equals("/pair") && m == Method.POST) return pair(s);
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^/(media|art)/(\\d+)$").matcher(p);
            String peer = who(s);
            if (mm.matches() && (peer != null || tokenOk(s, Long.parseLong(mm.group(2))))) return file(s, mm.group(1), Long.parseLong(mm.group(2)));
            if (peer == null) return err(Response.Status.UNAUTHORIZED, "key");
            if (p.equals("/state")) { JSONObject o; try { o = new JSONObject(MediaService.lastState); } catch (Exception e) { o = new JSONObject(); } o.put("libV", libV); return json(Response.Status.OK, o); }
            if (p.equals("/lib")) {
                JSONArray lib = Library.load(ctx), out = new JSONArray();
                for (int i = 0; i < lib.length(); i++) {
                    JSONObject t = lib.getJSONObject(i);
                    out.put(new JSONObject().put("id", t.optLong("id")).put("title", t.optString("title")).put("artist", t.optString("artist")).put("album", t.optString("album"))
                        .put("dur", t.optLong("dur")).put("genre", t.optString("genre")).put("art", t.optString("art").length() > 0));
                }
                return json(Response.Status.OK, new JSONObject().put("v", libV).put("tracks", out));
            }
            if (p.equals("/cmd") && m == Method.POST) {
                JSONObject b = body(s); String a = b.optString("a");
                if (!CMDS.contains(a)) return err(Response.Status.BAD_REQUEST, "cmd");
                host.cmd(a, b.optJSONObject("o") == null ? new JSONObject() : b.optJSONObject("o"));
                return json(Response.Status.OK, new JSONObject().put("ok", true));
            }
            if (p.equals("/cast") && m == Method.POST) return err(Response.Status.NOT_IMPLEMENTED, "unsupported");
            if (p.equals("/pull") && m == Method.POST) return pull(s, body(s));
            mm = java.util.regex.Pattern.compile("^/job/([0-9a-f]+)$").matcher(p);
            if (mm.matches()) { JSONObject j = jobs.get(mm.group(1)); return j != null ? json(Response.Status.OK, j) : err(Response.Status.NOT_FOUND, "job"); }
            if (p.equals("/unlink") && m == Method.POST) { kick(peer); return json(Response.Status.OK, new JSONObject().put("ok", true)); }
            return err(Response.Status.NOT_FOUND, "nf");
        } catch (Exception e) { return err(Response.Status.BAD_REQUEST, "bad"); }
    }

    Response pair(IHTTPSession s) throws Exception {
        if (System.currentTimeMillis() < lockUntil) return err(TOO_MANY, "locked");
        JSONObject b = body(s);
        if (!same(b.optString("secret"), secret)) {
            if (++fails >= 6) { lockUntil = System.currentTimeMillis() + 60000; secret = digits(5); fails = 0; host.changed(); }
            return err(Response.Status.FORBIDDEN, "code");
        }
        fails = 0;
        String id = hex(6), key = hex(24);
        String name = b.optString("name", "Device"); if (name.length() > 40) name = name.substring(0, 40);
        String plat = b.optString("platform"); if (plat.length() > 12) plat = plat.substring(0, 12);
        keys.put(id, new JSONObject().put("key", key).put("name", name).put("platform", plat)); saveKeys();
        final JSONObject rv = b.optJSONObject("reverse"); final String from = s.getRemoteIpAddress();
        if (rv != null) new Thread(() -> { try { reversePair(from, rv); } catch (Exception e) { } }).start();
        return json(Response.Status.OK, new JSONObject().put("ok", true).put("key", key).put("peerId", id)
            .put("info", new JSONObject().put("name", deviceName()).put("platform", "android").put("ip", ip).put("port", port)));
    }
    /** The device that connected also runs a server; pair back so both sides can control / send to each other. */
    void reversePair(String from, JSONObject rv) throws Exception {
        String rip = rv.optString("ip"); int rport = rv.optInt("port");
        if (from == null) return; from = from.replaceFirst("^::ffff:", "");
        if (!rip.equals(from) || !isPrivate(from)) return;
        boolean okPort = false; for (int q : PORTS) if (q == rport) okPort = true; if (!okPort) return;
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + rip + ":" + rport + "/pair").openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(6000); c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
        byte[] d = new JSONObject().put("secret", rv.optString("secret")).put("name", deviceName()).put("platform", "android").toString().getBytes("UTF-8");
        c.getOutputStream().write(d);
        if (c.getResponseCode() != 200) return;
        JSONObject j = new JSONObject(readAll(c.getInputStream()));
        if (!j.optBoolean("ok")) return;
        JSONObject info = j.getJSONObject("info");
        addOut(new JSONObject().put("id", j.getString("peerId")).put("name", info.optString("name")).put("platform", info.optString("platform"))
            .put("ip", rip).put("port", rport).put("key", j.getString("key")).toString());
        host.changed();
    }
    static String readAll(InputStream in) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0 && o.size() < 1 << 20) o.write(b, 0, n); in.close(); return o.toString("UTF-8"); }

    // ---------- files (Range aware) ----------
    Response file(IHTTPSession s, String kind, long id) {
        try {
            InputStream in; long size; String mime;
            if (kind.equals("art")) {
                String path = null; JSONArray lib = Library.load(ctx);
                for (int i = 0; i < lib.length(); i++) { JSONObject o = lib.getJSONObject(i); if (o.optLong("id") == id) { path = o.optString("art"); break; } }
                File f = path == null || path.isEmpty() ? null : new File(path);
                if (f == null || !f.isFile()) return err(Response.Status.NOT_FOUND, "nf");
                return cors(newFixedLengthResponse(Response.Status.OK, "image/jpeg", new FileInputStream(f), f.length()));
            }
            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(trackUri(id), "r");
            if (pfd == null) return err(Response.Status.NOT_FOUND, "nf");
            size = pfd.getStatSize(); mime = ctx.getContentResolver().getType(trackUri(id)); if (mime == null) mime = "audio/mpeg";
            long start = 0, end = size - 1; boolean part = false;
            String rg = s.getHeaders().get("range");
            if (rg != null) {
                java.util.regex.Matcher r = java.util.regex.Pattern.compile("^bytes=(\\d*)-(\\d*)$").matcher(rg.trim());
                if (r.matches()) {
                    String a = r.group(1), b = r.group(2);
                    if (a.isEmpty() && !b.isEmpty()) { start = Math.max(0, size - Long.parseLong(b)); }
                    else if (!a.isEmpty()) { start = Long.parseLong(a); if (!b.isEmpty()) end = Math.min(end, Long.parseLong(b)); }
                    part = true;
                    if (start > end || start >= size) { pfd.close(); Response x = cors(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")); x.addHeader("Content-Range", "bytes */" + size); return x; }
                }
            }
            FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            if (start > 0) fis.getChannel().position(start);
            long len = end - start + 1;
            Response r = newFixedLengthResponse(part ? Response.Status.PARTIAL_CONTENT : Response.Status.OK, mime, fis, len);
            r.addHeader("Accept-Ranges", "bytes");
            if (part) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            return cors(r);
        } catch (Exception e) { return err(Response.Status.NOT_FOUND, "nf"); }
    }

    // ---------- receiving songs ----------
    Response pull(IHTTPSession s, JSONObject b) throws Exception {
        String url = b.optString("url"), name = b.optString("name", "song.mp3");
        final URL u; String from = s.getRemoteIpAddress(); if (from != null) from = from.replaceFirst("^::ffff:", "");
        try { u = new URL(url); } catch (Exception e) { return err(Response.Status.BAD_REQUEST, "url"); }
        boolean okPort = false; for (int q : PORTS) if (q == u.getPort()) okPort = true;
        if (!u.getProtocol().equals("http") || from == null || !u.getHost().equals(from) || !isPrivate(from) || !okPort) return err(Response.Status.BAD_REQUEST, "url");
        name = new File(name).getName().replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_"); if (name.length() > 120) name = name.substring(name.length() - 120);
        if (!name.toLowerCase().matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|oga|opus)$")) return err(Response.Status.BAD_REQUEST, "type");
        final String fn = name; final String id = hex(6);
        final JSONObject job = new JSONObject().put("done", false).put("pct", 0).put("name", fn); jobs.put(id, job);
        new Thread(() -> {
            try { download(u, fn, job); }
            catch (Exception e) { try { job.put("done", true).put("err", String.valueOf(e.getMessage())); } catch (Exception x) { } }
        }).start();
        return json(Response.Status.OK, new JSONObject().put("ok", true).put("job", id));
    }
    static String mimeFor(String n) {
        n = n.toLowerCase();
        return n.endsWith(".m4a") || n.endsWith(".aac") ? "audio/mp4" : n.endsWith(".flac") ? "audio/flac" : n.endsWith(".wav") ? "audio/x-wav" : n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus") ? "audio/ogg" : "audio/mpeg";
    }
    void download(URL u, String name, JSONObject job) throws Exception {
        HttpURLConnection c = (HttpURLConnection) u.openConnection(); c.setConnectTimeout(8000); c.setReadTimeout(15000);
        if (c.getResponseCode() != 200) throw new IOException("http" + c.getResponseCode());
        long total = c.getContentLengthLong(), got = 0;
        OutputStream out; Uri uri = null; File dest = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name); v.put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(name));
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Air.INC"); v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            uri = ctx.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("store");
            out = ctx.getContentResolver().openOutputStream(uri);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Air.INC"); dir.mkdirs();
            dest = new File(dir, name); String ext = name.substring(name.lastIndexOf('.')), base = name.substring(0, name.lastIndexOf('.'));
            for (int i = 2; dest.exists(); i++) dest = new File(dir, base + " (" + i + ")" + ext);
            out = new FileOutputStream(dest);
        }
        try {
            InputStream in = c.getInputStream(); byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) > 0) {
                got += n; if (got > MAX_PULL) throw new IOException("too big");
                out.write(buf, 0, n); job.put("pct", total > 0 ? (int) (got * 100 / total) : 0);
            }
            out.close();
        } catch (Exception e) {
            try { out.close(); } catch (Exception x) { }
            if (uri != null) ctx.getContentResolver().delete(uri, null, null); else if (dest != null) dest.delete();
            throw e;
        }
        if (uri != null) { ContentValues v = new ContentValues(); v.put(MediaStore.Audio.Media.IS_PENDING, 0); ctx.getContentResolver().update(uri, v, null, null); }
        else MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
        job.put("done", true).put("pct", 100).put("file", name);
        host.pulled(name);
    }
}
