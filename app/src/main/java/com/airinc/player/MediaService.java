package com.airinc.player;

import android.app.*;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/** Foreground media service: lock-screen + Quick Settings media controls. */
public class MediaService extends Service {
    public interface Listener { void cmd(String c, long pos); }
    public static Listener listener;
    public static boolean running;
    private static final String CH = "air_media";
    private MediaSession session;
    private String title = "Air.INC", artist = "";
    private boolean playing; private long pos, dur;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "AirInc");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("play", 0); }
            @Override public void onPause() { send("pause", 0); }
            @Override public void onSkipToNext() { send("next", 0); }
            @Override public void onSkipToPrevious() { send("prev", 0); }
            @Override public void onSeekTo(long p) { send("seek", p); }
        });
        session.setActive(true);
    }

    private void send(String c, long p) { if (listener != null) listener.cmd(c, p); }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && i.getAction() != null) {
            String a = i.getAction();
            if (a.equals("update")) {
                title = i.getStringExtra("title"); artist = i.getStringExtra("artist");
                playing = i.getBooleanExtra("playing", false);
                pos = i.getLongExtra("pos", 0); dur = i.getLongExtra("dur", 0);
            } else if (a.equals("toggle")) send(playing ? "pause" : "play", 0);
            else send(a, 0);
        }
        refresh();
        return START_STICKY;
    }

    private PendingIntent act(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, MediaService.class).setAction(a),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void refresh() {
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur).build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f).build());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(artist)
            .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", act("prev", 1))
            .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Pause" : "Play", act("toggle", 2))
            .addAction(android.R.drawable.ic_media_next, "Next", act("next", 3))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    @Override public void onDestroy() { running = false; session.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
