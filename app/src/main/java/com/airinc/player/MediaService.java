package com.airinc.player;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.*;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;

/** Native music engine: background playback, EQ, stats, alarm, lock-screen / Quick Settings controls. */
public class MediaService extends Service implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {
    public interface Listener { void state(String json); void alarm(); }
    public static Listener listener;
    public static boolean running;
    public static String lastState = "{\"idx\":-1,\"playing\":false,\"pos\":0,\"dur\":0,\"vol\":1,\"shuf\":false,\"rep\":false}";
    static final String CH = "air_media";

    MediaPlayer mp; Equalizer eq; BassBoost bb; MediaSession session; AudioManager am; AudioFocusRequest fr;
    final Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences sp; JSONObject st;
    JSONArray lib = new JSONArray();
    int idx = -1, tickN = 0;
    boolean playing, shuffle, repeat, prepared, loading, wantPlay, resumeOnFocus;
    float vol = 1f;
    Bitmap artBmp; String artFor = "";
    final AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    final BroadcastReceiver noisy = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { pause(); } };
    final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (playing && prepared) {
                addListen(500); push();
                if (++tickN % 20 == 0) { flushStats(); refresh(); }
            }
            h.postDelayed(this, 500);
        }
    };

    // ---------- helpers ----------
    static int I(Intent i, String k) { Object o = i.getExtras() == null ? null : i.getExtras().get(k); return o instanceof Number ? ((Number) o).intValue() : 0; }
    static long L(Intent i, String k) { Object o = i.getExtras() == null ? null : i.getExtras().get(k); return o instanceof Number ? ((Number) o).longValue() : 0; }
    static float F(Intent i, String k) { Object o = i.getExtras() == null ? null : i.getExtras().get(k); return o instanceof Number ? ((Number) o).floatValue() : 1f; }

    public static JSONObject readStats(SharedPreferences sp) {
        JSONObject o;
        try { o = new JSONObject(sp.getString("stats", "{}")); } catch (Exception e) { o = new JSONObject(); }
        try {
            if (!o.has("p")) o.put("p", 0);
            if (!o.has("s")) o.put("s", 0);
            if (!o.has("ms")) o.put("ms", 0L);
            if (!o.has("w")) { JSONArray w = new JSONArray(); for (int i = 0; i < 7; i++) w.put(0); o.put("w", w); }
            if (!o.has("days")) o.put("days", new JSONArray());
        } catch (Exception e) { }
        return o;
    }

    public static String eqInfo(Context c) {
        SharedPreferences sp = c.getSharedPreferences("air", 0);
        JSONObject o = new JSONObject();
        try {
            String cached = sp.getString("eqinfo", null);
            JSONObject base;
            if (cached != null) base = new JSONObject(cached);
            else {
                base = new JSONObject();
                MediaPlayer m = new MediaPlayer();
                try {
                    Equalizer e = new Equalizer(0, m.getAudioSessionId());
                    short n = e.getNumberOfBands(); short[] r = e.getBandLevelRange();
                    JSONArray f = new JSONArray();
                    for (short b = 0; b < n; b++) f.put(e.getCenterFreq(b) / 1000);
                    base.put("n", (int) n); base.put("min", (int) r[0]); base.put("max", (int) r[1]); base.put("freqs", f);
                    e.release();
                } catch (Throwable t) { base.put("n", 0); }
                m.release();
                sp.edit().putString("eqinfo", base.toString()).apply();
            }
            o = base;
            JSONArray lv = new JSONArray(sp.getString("eq", "[]")), out = new JSONArray();
            for (int i = 0; i < o.optInt("n"); i++) out.put(lv.optInt(i, 0));
            o.put("lv", out); o.put("bass", sp.getInt("bass", 0));
        } catch (Exception e) { try { o.put("n", 0); } catch (Exception x) { } }
        return o.toString();
    }

    // ---------- lifecycle ----------
    @Override public void onCreate() {
        super.onCreate();
        running = true;
        sp = getSharedPreferences("air", 0);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        st = readStats(sp);
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "AirInc");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long p) { seek(p); }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        lib = Library.load(this);
        shuffle = sp.getBoolean("shuf", false); repeat = sp.getBoolean("rep", false); vol = sp.getFloat("vol", 1f);
        reindex(sp.getLong("lastId", -1));
        h.post(ticker);
    }

    void reindex(long id) {
        idx = -1;
        for (int i = 0; i < lib.length(); i++) if (lib.optJSONObject(i).optLong("id") == id) { idx = i; break; }
    }

    @Override public int onStartCommand(Intent i, int flags, int sid) {
        refresh();   // always enter foreground quickly
        String a = i == null ? null : i.getAction();
        if (a != null) handle(a, i);
        if (!playing && !prepared && !wantPlay && !loading) { stopForeground(true); stopSelf(); }
        return START_NOT_STICKY;
    }

    void handle(String a, Intent i) {
        switch (a) {
            case "play": playIdx(I(i, "i"), true); break;
            case "toggle": if (playing) pause(); else resume(); break;
            case "resume": resume(); break;
            case "pause": pause(); break;
            case "next": next(false); break;
            case "prev": prev(); break;
            case "seek": seek(L(i, "ms")); break;
            case "vol": vol = Math.max(0f, Math.min(1f, F(i, "v"))); sp.edit().putFloat("vol", vol).apply(); if (mp != null) mp.setVolume(vol, vol); push(); break;
            case "shuf": shuffle = i.getBooleanExtra("b", false); sp.edit().putBoolean("shuf", shuffle).apply(); push(); break;
            case "rep": repeat = i.getBooleanExtra("b", false); sp.edit().putBoolean("rep", repeat).apply(); push(); break;
            case "reload": { long id = idx >= 0 ? lib.optJSONObject(idx).optLong("id") : -1; lib = Library.load(this); reindex(id); refresh(); push(); break; }
            case "alarm": alarm(); break;
            case "eq": setBand(I(i, "b"), I(i, "mb")); break;
            case "eqall": { int[] lv = i.getIntArrayExtra("lv"); if (lv != null) for (int b = 0; b < lv.length; b++) setBand(b, lv[b]); break; }
            case "bass": sp.edit().putInt("bass", I(i, "s")).apply(); applyFx(); break;
        }
    }

    // ---------- engine ----------
    void ensureMp() {
        if (mp != null) return;
        mp = new MediaPlayer();
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(attrs);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener((m, w, x) -> { prepared = false; loading = false; playing = false; refresh(); push(); return true; });
        mp.setOnPreparedListener(m -> {
            prepared = true; loading = false; m.setVolume(vol, vol);
            if (wantPlay && focus()) { m.start(); playing = true; }
            refresh(); push();
        });
        try {
            int sid = mp.getAudioSessionId();
            eq = new Equalizer(0, sid); eq.setEnabled(true);
            bb = new BassBoost(0, sid); bb.setEnabled(true);
            applyFx();
        } catch (Throwable t) { }
    }

    void applyFx() {
        try {
            if (eq != null) {
                JSONArray a = new JSONArray(sp.getString("eq", "[]"));
                short[] r = eq.getBandLevelRange();
                for (short b = 0; b < eq.getNumberOfBands(); b++) eq.setBandLevel(b, (short) Math.max(r[0], Math.min(r[1], a.optInt(b, 0))));
            }
        } catch (Throwable t) { }
        try { if (bb != null && bb.getStrengthSupported()) bb.setStrength((short) sp.getInt("bass", 0)); } catch (Throwable t) { }
    }

    void setBand(int b, int mb) {
        try {
            JSONArray a = new JSONArray(sp.getString("eq", "[]"));
            a.put(b, mb);
            sp.edit().putString("eq", a.toString()).apply();
        } catch (Exception e) { }
        applyFx();
    }

    boolean focus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (fr == null) fr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(this).build();
            return am.requestAudioFocus(fr) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override public void onAudioFocusChange(int c) {
        if (c == AudioManager.AUDIOFOCUS_LOSS) pause();
        else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (playing) { pause(); resumeOnFocus = true; } }
        else if (c == AudioManager.AUDIOFOCUS_GAIN) { if (resumeOnFocus) { resumeOnFocus = false; resume(); } if (mp != null) mp.setVolume(vol, vol); }
        else if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { if (mp != null) mp.setVolume(vol * .3f, vol * .3f); }
    }

    void playIdx(int i, boolean auto) {
        if (lib.length() == 0) lib = Library.load(this);
        int n = lib.length(); if (n == 0) return;
        idx = ((i % n) + n) % n;
        JSONObject t = lib.optJSONObject(idx);
        ensureMp();
        try {
            mp.reset(); prepared = false; playing = false; loading = true; wantPlay = auto;
            mp.setDataSource(this, Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(t.optLong("id"))));
            mp.prepareAsync();
            sp.edit().putLong("lastId", t.optLong("id")).apply();
            if (auto) addPlay();
        } catch (Exception e) { loading = false; wantPlay = false; }
        refresh(); push();
    }

    void resume() {
        if (idx < 0) { playIdx(0, true); return; }
        if (!prepared) { if (loading) wantPlay = true; else playIdx(idx, true); return; }
        if (focus()) { mp.start(); playing = true; }
        refresh(); push();
    }

    void pause() {
        try { if (mp != null && playing) mp.pause(); } catch (Exception e) { }
        playing = false; wantPlay = false; flushStats(); refresh(); push();
    }

    void next(boolean auto) {
        int n = lib.length(); if (n == 0) return;
        try { if (!auto && prepared && mp.getCurrentPosition() < 20000) { st.put("s", st.optInt("s") + 1); flushStats(); } } catch (Exception e) { }
        int ni = shuffle && n > 1 ? (idx + 1 + (int) (Math.random() * (n - 1))) % n : idx + 1;
        playIdx(ni, true);
    }

    void prev() {
        try { if (prepared && mp.getCurrentPosition() > 3000) { seek(0); return; } } catch (Exception e) { }
        playIdx(idx - 1, true);
    }

    void seek(long ms) { try { if (prepared) mp.seekTo((int) ms); } catch (Exception e) { } refresh(); push(); }

    @Override public void onCompletion(MediaPlayer m) { if (repeat) { m.seekTo(0); m.start(); } else next(true); }

    void alarm() {
        sp.edit().putBoolean("alarm_on", false).apply();
        if (lib.length() == 0) lib = Library.load(this);
        vol = 0.1f;
        if (lib.length() > 0) playIdx(idx >= 0 ? idx : 0, true);
        else { try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).play(); } catch (Exception e) { } }
        h.postDelayed(new Runnable() { @Override public void run() { vol = Math.min(1f, vol + .06f); if (mp != null) mp.setVolume(vol, vol); if (vol < 1f) h.postDelayed(this, 1000); } }, 1000);
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(new long[]{0, 400, 250, 400}, -1); } catch (Exception e) { }
        if (listener != null) listener.alarm();
    }

    // ---------- stats ----------
    void addPlay() {
        try {
            Calendar c = Calendar.getInstance();
            st.put("p", st.optInt("p") + 1);
            JSONArray w = st.getJSONArray("w"); int d = c.get(Calendar.DAY_OF_WEEK) - 1; w.put(d, w.optInt(d) + 1);
            int mo = c.get(Calendar.MONTH) + 1;
            if (st.optInt("mo") != mo) { st.put("mo", mo); st.put("days", new JSONArray()); }
            JSONArray ds = st.getJSONArray("days"); int dm = c.get(Calendar.DAY_OF_MONTH); boolean has = false;
            for (int i = 0; i < ds.length(); i++) if (ds.optInt(i) == dm) has = true;
            if (!has) ds.put(dm);
        } catch (Exception e) { }
        flushStats();
    }
    void addListen(long ms) { try { st.put("ms", st.optLong("ms") + ms); } catch (Exception e) { } }
    void flushStats() { sp.edit().putString("stats", st.toString()).apply(); }

    // ---------- state out ----------
    JSONObject cur() { return idx >= 0 ? lib.optJSONObject(idx) : null; }

    void push() {
        long pos = 0, dur = 0; JSONObject t = cur();
        try { if (prepared && mp != null) { pos = mp.getCurrentPosition(); dur = mp.getDuration(); } else if (t != null) dur = t.optLong("dur"); } catch (Exception e) { }
        String js = "{\"idx\":" + idx + ",\"playing\":" + playing + ",\"pos\":" + pos + ",\"dur\":" + dur + ",\"vol\":" + vol + ",\"shuf\":" + shuffle + ",\"rep\":" + repeat + "}";
        lastState = js;
        if (listener != null) listener.state(js);
    }

    PendingIntent act(String a, int code) {
        Intent i = new Intent(this, MediaService.class).setAction(a);
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, code, i, f) : PendingIntent.getService(this, code, i, f);
    }

    void refresh() {
        JSONObject t = cur();
        String title = t != null ? t.optString("title") : "Air.INC", artist = t != null ? t.optString("artist") : "";
        String art = t != null ? t.optString("art") : "";
        long dur = t != null ? t.optLong("dur") : 0, pos = 0;
        try { if (prepared && mp != null) { pos = mp.getCurrentPosition(); dur = mp.getDuration(); } } catch (Exception e) { }
        if (!art.equals(artFor)) { artBmp = art.isEmpty() ? null : AirWidget.decode(art, 512); artFor = art; }
        MediaMetadata.Builder mb = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist).putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (artBmp != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBmp).putBitmap(MediaMetadata.METADATA_KEY_ART, artBmp);
        session.setMetadata(mb.build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f).build());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(artist)
            .setLargeIcon(artBmp != null ? artBmp : BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", act("prev", 1))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", act("toggle", 2))
            .addAction(android.R.drawable.ic_media_next, "Next", act("next", 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
        sp.edit().putString("np_title", title).putString("np_artist", artist).putString("np_art", art).putBoolean("np_playing", playing)
            .putLong("np_pos", pos).putLong("np_dur", dur).putLong("np_ts", System.currentTimeMillis()).apply();
        AirWidget.refreshAll(this);
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        flushStats();
        try { unregisterReceiver(noisy); } catch (Exception e) { }
        try { if (eq != null) eq.release(); if (bb != null) bb.release(); } catch (Exception e) { }
        if (mp != null) mp.release();
        session.release();
        sp.edit().putBoolean("np_playing", false).apply();
        AirWidget.refreshAll(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
