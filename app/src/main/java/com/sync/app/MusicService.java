package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {

    public static final String CHANNEL_ID   = "sync_music";
    public static final String ACTION_PLAY  = "com.sync.app.PLAY";
    public static final String ACTION_PAUSE = "com.sync.app.PAUSE";
    public static final String ACTION_NEXT  = "com.sync.app.NEXT";
    public static final String ACTION_PREV  = "com.sync.app.PREV";
    public static final String ACTION_UPDATE = "com.sync.app.UPDATE";
    public static final String ACTION_STOP  = "com.sync.app.STOP";

    public static final String EXTRA_TITLE   = "title";
    public static final String EXTRA_ARTIST  = "artist";
    public static final String EXTRA_THUMB   = "thumb";
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_DURATION = "duration";

    private final IBinder binder = new LocalBinder();
    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notifManager;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private String currentTitle  = "";
    private String currentArtist = "";
    private String currentThumb  = "";
    private boolean isPlaying    = false;
    private long    position     = 0;
    private long    duration     = 0;
    private Bitmap  albumArt     = null;

    // MainActivity 콜백
    private Callback callback;
    public interface Callback {
        void onPlay();
        void onPause();
        void onNext();
        void onPrev();
    }
    public void setCallback(Callback cb) { this.callback = cb; }

    public class LocalBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaSession();
        acquireWakeLock();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SYNC 음악 재생",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("음악 재생 중 알림");
            ch.setShowBadge(false);
            notifManager = getSystemService(NotificationManager.class);
            notifManager.createNotificationChannel(ch);
        } else {
            notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "SYNCSession");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()     { if (callback != null) callback.onPlay(); }
            @Override public void onPause()    { if (callback != null) callback.onPause(); }
            @Override public void onSkipToNext() { if (callback != null) callback.onNext(); }
            @Override public void onSkipToPrevious() { if (callback != null) callback.onPrev(); }
            @Override public void onStop()     { stopSelf(); }
        });
        mediaSession.setActive(true);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SYNC::MusicWakeLock");
        wakeLock.acquire(12 * 60 * 60 * 1000L); // 최대 12시간
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        MediaButtonReceiver.handleIntent(mediaSession, intent);

        String action = intent.getAction();
        if (action == null) action = "";

        switch (action) {
            case ACTION_PLAY:
                if (callback != null) callback.onPlay();
                break;
            case ACTION_PAUSE:
                if (callback != null) callback.onPause();
                break;
            case ACTION_NEXT:
                if (callback != null) callback.onNext();
                break;
            case ACTION_PREV:
                if (callback != null) callback.onPrev();
                break;
            case ACTION_STOP:
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_UPDATE:
                currentTitle  = intent.getStringExtra(EXTRA_TITLE);
                currentArtist = intent.getStringExtra(EXTRA_ARTIST);
                currentThumb  = intent.getStringExtra(EXTRA_THUMB);
                isPlaying     = intent.getBooleanExtra(EXTRA_PLAYING, false);
                position      = intent.getLongExtra(EXTRA_POSITION, 0);
                duration      = intent.getLongExtra(EXTRA_DURATION, 0);
                if (currentTitle == null) currentTitle = "";
                if (currentArtist == null) currentArtist = "";
                if (currentThumb == null) currentThumb = "";
                loadAlbumArtAndNotify();
                break;
        }
        return START_STICKY;
    }

    private void loadAlbumArtAndNotify() {
        final String thumbUrl = currentThumb;
        executor.submit(() -> {
            Bitmap bmp = null;
            try {
                InputStream in = new URL(thumbUrl).openStream();
                bmp = BitmapFactory.decodeStream(in);
                in.close();
            } catch (Exception ignored) {}
            albumArt = bmp;
            updateNotificationAndSession();
        });
    }

    private void updateNotificationAndSession() {
        updateMediaSession();
        Notification notif = buildNotification();
        startForeground(1, notif);
    }

    private void updateMediaSession() {
        // 메타데이터
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (albumArt != null)
            meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
        mediaSession.setMetadata(meta.build());

        // 재생 상태
        PlaybackStateCompat.Builder state = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(
                        isPlaying
                            ? PlaybackStateCompat.STATE_PLAYING
                            : PlaybackStateCompat.STATE_PAUSED,
                        position, 1.0f);
        mediaSession.setPlaybackState(state.build());
    }

    private Notification buildNotification() {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPi = buildActionPi(ACTION_PREV, 1);
        PendingIntent playPi = buildActionPi(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPi = buildActionPi(ACTION_NEXT, 3);

        androidx.media.app.NotificationCompat.MediaStyle style =
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setContentTitle(currentTitle.isEmpty() ? "SYNC" : currentTitle)
                        .setContentText(currentArtist.isEmpty() ? "재생 중" : currentArtist)
                        .setContentIntent(openApp)
                        .setStyle(style)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(false)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .addAction(android.R.drawable.ic_media_previous, "이전", prevPi)
                        .addAction(
                                isPlaying
                                    ? android.R.drawable.ic_media_pause
                                    : android.R.drawable.ic_media_play,
                                isPlaying ? "일시정지" : "재생",
                                playPi)
                        .addAction(android.R.drawable.ic_media_next, "다음", nextPi);

        if (albumArt != null) builder.setLargeIcon(albumArt);
        return builder.build();
    }

    private PendingIntent buildActionPi(String action, int reqCode) {
        Intent intent = new Intent(this, MusicService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public MediaSessionCompat getMediaSession() { return mediaSession; }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) { return true; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        executor.shutdown();
    }
}
