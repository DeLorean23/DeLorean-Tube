package de.delorean23.tube;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Wecker klingelt: startet die gewählte Playlist / das Video, wird langsam lauter.
 * Kein Internet oder Video geht nicht: normaler Weckton.
 * Created by DeLorean23
 */
public class AlarmService extends Service {

    public static final String ACTION_RING = "ring", ACTION_STOP = "stop",
            ACTION_SNOOZE = "snooze", ACTION_KEEP = "keep";
    private static final int NOTIF_ID = 7700;
    private static final int SNOOZE_MIN = 10;
    private static final long RAMP_MS = 30_000, FALLBACK_AFTER_MS = 25_000, MAX_RING_MS = 30 * 60_000;

    /** Läuft gerade ein Wecker? (für AlarmActivity) */
    static volatile Alarms.Alarm ringing;
    static volatile String nowTitle;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private MediaPlayer fallback;
    private PowerManager.WakeLock wake;
    private int alarmId;
    private long startedAt;
    private float targetVolume = 1f;
    private boolean ramp;

    private final Runnable rampStep = new Runnable() {
        @Override
        public void run() {
            if (controller == null || ringing == null) return;
            float f = Math.min(1f, (System.currentTimeMillis() - startedAt) / (float) RAMP_MS);
            controller.setVolume(Math.max(0.05f, f));
            if (f < 1f) App.MAIN.postDelayed(this, 1000);
        }
    };

    private final Runnable watchdog = () -> {
        if (ringing != null && (controller == null || !controller.isPlaying())) startFallback();
    };

    private final Runnable autoStop = this::stopRinging;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_RING.equals(a)) {
            ring(intent.getIntExtra("id", 0));
        } else if (ACTION_SNOOZE.equals(a)) {
            if (ringing != null) Alarms.snooze(this, ringing.id, SNOOZE_MIN);
            stopRinging();
        } else if (ACTION_KEEP.equals(a)) {
            keepListening();
        } else {
            stopRinging();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ klingeln

    private void ring(int id) {
        Alarms.Alarm al = Alarms.get(id);
        if (al == null) {
            al = new Alarms.Alarm();
            al.id = id;
        }
        if (ringing != null) cleanupPlayback(false);
        ringing = al;
        alarmId = id;
        nowTitle = al.sourceLabel;
        startedAt = System.currentTimeMillis();
        ramp = al.ramp;

        Notification n = notification("Wecker " + al.time(), al.sourceLabel);
        int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;
        ServiceCompat.startForeground(this, NOTIF_ID, n, type);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeLoreanTube:alarm");
            wake.acquire(2 * 60_000L);
        }
        // Bildschirm an (falls Vollbild-Benachrichtigung nicht erlaubt ist, wenigstens versuchen)
        try {
            startActivity(new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }

        // Lautstärke einstellen
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int vol = Math.max(1, Math.round(max * al.volume / 100f));
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
            } catch (Throwable ignored) {
            }
        }

        App.MAIN.postDelayed(watchdog, FALLBACK_AFTER_MS);
        App.MAIN.postDelayed(autoStop, MAX_RING_MS);

        final Alarms.Alarm alarm = al;
        App.IO.execute(() -> {
            List<Item> items = new ArrayList<>();
            try {
                if (alarm.source.startsWith("video:")) {
                    Item it = new Item();
                    it.url = alarm.source.substring(6);
                    it.title = alarm.sourceLabel;
                    items.add(it);
                } else {
                    items.addAll(CarLibrary.load(alarm.source));
                }
            } catch (Throwable ignored) {
            }
            App.MAIN.post(() -> startPlayback(items, alarm.shuffle));
        });
    }

    private void startPlayback(List<Item> items, boolean shuffle) {
        if (ringing == null) return;
        if (items.isEmpty()) {
            startFallback();
            return;
        }
        int start = shuffle ? new java.util.Random().nextInt(items.size()) : 0;
        PlayQueue.playList(items, start, shuffle, "Wecker");
        Item first = PlayQueue.current();
        if (first == null) {
            startFallback();
            return;
        }
        nowTitle = first.title != null ? first.title : nowTitle;
        updateNotification();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Throwable e) {
                startFallback();
                return;
            }
            if (ringing == null) {
                releaseController();
                return;
            }
            controller.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    if (ringing != null) startFallback();
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying && fallback != null) stopFallback();
                }

                @Override
                public void onMediaMetadataChanged(@NonNull MediaMetadata md) {
                    if (md.title != null) {
                        nowTitle = md.title.toString();
                        updateNotification();
                    }
                }
            });
            MediaMetadata.Builder md = new MediaMetadata.Builder();
            if (first.title != null) md.setTitle(first.title);
            if (first.uploader != null) md.setArtist(first.uploader);
            MediaItem request = new MediaItem.Builder()
                    .setMediaId(TubeSources.mediaId(first.url, Playback.wantedHeight(this), Playback.audioOnly()))
                    .setMediaMetadata(md.build()).build();
            controller.setVolume(ramp ? 0.05f : targetVolume);
            controller.setMediaItem(request);
            controller.prepare();
            controller.play();
            if (ramp) App.MAIN.post(rampStep);
        }, App.MAIN::post);
    }

    // ------------------------------------------------------------ Ersatz-Weckton

    private void startFallback() {
        if (fallback != null || ringing == null) return;
        try {
            if (controller != null) controller.pause();
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (u == null) return;
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(this, u);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            fallback = mp;
            nowTitle = "Weckton (Video nicht verfügbar)";
            updateNotification();
        } catch (Throwable ignored) {
        }
    }

    private void stopFallback() {
        if (fallback != null) {
            try {
                fallback.stop();
            } catch (Throwable ignored) {
            }
            fallback.release();
            fallback = null;
        }
    }

    // ------------------------------------------------------------ beenden

    /** Stopp: Ton aus. */
    private void stopRinging() {
        if (controller != null) controller.pause();
        cleanupPlayback(true);
        finishService();
    }

    /** Weiterhören: Wecker aus, aber Video/Playlist läuft normal weiter. */
    private void keepListening() {
        App.MAIN.removeCallbacks(rampStep);
        if (controller != null) controller.setVolume(1f);
        if (fallback != null) {
            stopFallback();
        }
        cleanupPlayback(true);
        finishService();
    }

    private void cleanupPlayback(boolean restoreVolume) {
        App.MAIN.removeCallbacks(rampStep);
        App.MAIN.removeCallbacks(watchdog);
        App.MAIN.removeCallbacks(autoStop);
        stopFallback();
        if (controller != null && restoreVolume) controller.setVolume(1f);
        releaseController();
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
    }

    private void releaseController() {
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controllerFuture = null;
        controller = null;
    }

    private void finishService() {
        ringing = null;
        AlarmActivity.closeAll();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (ringing != null) cleanupPlayback(true);
        ringing = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------ Benachrichtigung

    private PendingIntent self(String action, int code) {
        Intent i = new Intent(this, AlarmService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    private Notification notification(String title, String text) {
        Intent full = new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent fullPi = PendingIntent.getActivity(this, 7701, full, PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return new NotificationCompat.Builder(this, App.CH_ALARM)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(0, "Stopp", self(ACTION_STOP, 7702))
                .addAction(0, "Schlummern " + SNOOZE_MIN + " Min.", self(ACTION_SNOOZE, 7703))
                .addAction(0, "Weiterhören", self(ACTION_KEEP, 7704))
                .build();
    }

    private void updateNotification() {
        Alarms.Alarm al = ringing;
        if (al == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, notification("Wecker " + al.time(), nowTitle));
        AlarmActivity.refreshAll();
    }

    static int snoozeMinutes() {
        return SNOOZE_MIN;
    }
}
