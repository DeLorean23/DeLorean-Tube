package de.delorean23.tube;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaSession;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Wiedergabe im Hintergrund: läuft weiter, wenn die App im Hintergrund ist oder der Bildschirm aus ist.
 * Steuerung über Benachrichtigung, Sperrbildschirm und Kopfhörer/Lenkrad (Vor/Zurück).
 * Kümmert sich um Warteschlange, Autoplay, Schlaf-Timer und merkt sich die Position.
 * Created by DeLorean23
 */
public class PlaybackService extends MediaLibraryService {

    private static PlaybackService instance;

    private MediaLibrarySession session;
    private ExoPlayer player;
    private int playToken;

    // SponsorBlock und A–B-Schleife
    private volatile List<SponsorBlock.Segment> segments = new ArrayList<>();
    private String segmentsFor;
    private static long abA = -1, abB = -1;
    private static String abUrl;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkSkips();
            App.MAIN.postDelayed(this, 250);
        }
    };

    private final Runnable savePosition = new Runnable() {
        @Override
        public void run() {
            saveCurrentPosition(false);
            App.MAIN.postDelayed(this, 5_000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new TubeSources.SourceFactory())
                // Puffer: startet nach 2,5 s, lädt bis 90 s voraus, nach einem Hänger erst ab 8 s weiter
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(30_000, 90_000, 2_500, 8_000)
                        .build())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // abgelaufene Links: beim nächsten Mal neu laden
                MediaItem cur = player.getCurrentMediaItem();
                if (cur != null) App.forgetStreamInfo(TubeSources.urlOf(cur));
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) onEnded();
            }

            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                TubeWidget.update(PlaybackService.this);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                TubeWidget.update(PlaybackService.this);
                App.MAIN.removeCallbacks(savePosition);
                App.MAIN.removeCallbacks(tick);
                if (isPlaying) {
                    App.MAIN.postDelayed(savePosition, 5_000);
                    App.MAIN.post(tick);
                } else {
                    saveCurrentPosition(true);
                }
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                AudioFx.attach(audioSessionId);
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                // neues Video: ein noch laufendes Autoplay-Laden ist überholt
                playToken++;
                loadSegments(TubeSources.urlOf(mediaItem));
                if (abUrl != null && !abUrl.equals(TubeSources.urlOf(mediaItem))) clearAb();
                // Warteschlange auf das laufende Video setzen (z. B. von außen gestartet)
                String u = TubeSources.urlOf(mediaItem);
                if (u != null) {
                    Item it = new Item();
                    it.url = u;
                    MediaMetadata md = mediaItem.mediaMetadata;
                    it.title = md.title != null ? md.title.toString() : null;
                    it.uploader = md.artist != null ? md.artist.toString() : null;
                    PlayQueue.ensureCurrent(it);
                }
            }
        });

        Intent open = new Intent(this, PlayerActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        AudioFx.attach(player.getAudioSessionId());

        session = new MediaLibrarySession.Builder(this, new QueuePlayer(player), new SessionCallback())
                .setSessionActivity(pi)
                .build();
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // App aus der Übersicht gewischt: weiterspielen, wenn gerade etwas läuft
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        saveCurrentPosition(true);
        App.MAIN.removeCallbacks(savePosition);
        App.MAIN.removeCallbacks(tick);
        AudioFx.release();
        instance = null;
        TubeWidget.update(this);
        PlayQueue.clear();
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ von außen (gleicher Prozess)

    /** Schlaf-Timer ist abgelaufen. */
    public static void pauseFromTimer() {
        if (instance != null && instance.player != null) instance.player.pause();
    }

    /** Video aus der Warteschlange abspielen (Warteschlangen-Liste angetippt). */
    public static void playQueueIndex(int pos) {
        Item it = PlayQueue.jump(pos);
        if (instance != null && it != null) instance.playItem(it, true);
    }

    /** Für das Startbildschirm-Widget. null = nichts geladen. */
    @Nullable
    public static MediaMetadata nowPlaying() {
        if (instance == null || instance.player == null || instance.player.getCurrentMediaItem() == null) return null;
        return instance.player.getMediaMetadata();
    }

    public static boolean isPlaying() {
        return instance != null && instance.player != null && instance.player.getPlayWhenReady();
    }

    /** Play/Pause vom Widget; false = es läuft kein Player. */
    public static boolean togglePlay() {
        if (instance == null || instance.player == null || instance.player.getCurrentMediaItem() == null) return false;
        if (instance.player.getPlayWhenReady()) {
            instance.player.pause();
        } else {
            if (instance.player.getPlaybackState() == Player.STATE_IDLE) instance.player.prepare();
            instance.player.play();
        }
        return true;
    }

    public static void skipNext() {
        if (instance != null) instance.next(false);
    }

    public static void skipPrevious() {
        if (instance != null) instance.previous();
    }

    public static void applyRepeat() {
        if (instance != null && instance.player != null) {
            instance.player.setRepeatMode(PlayQueue.repeat() == PlayQueue.REPEAT_ONE
                    ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
    }

    // ------------------------------------------------------------ SponsorBlock / A–B

    private void loadSegments(String url) {
        segments = new ArrayList<>();
        segmentsFor = url;
        final String vid = Util.videoId(url);
        if (vid == null || !SponsorBlock.enabled()) return;
        App.IO.execute(() -> {
            List<SponsorBlock.Segment> s = SponsorBlock.segments(vid);
            App.MAIN.post(() -> {
                if (url.equals(segmentsFor)) segments = s;
            });
        });
    }

    private void checkSkips() {
        if (player == null || !player.isPlaying()) return;
        long pos = player.getCurrentPosition();
        // A–B-Schleife
        if (abA >= 0 && abB > abA && pos >= abB) {
            player.seekTo(abA);
            return;
        }
        // SponsorBlock
        for (SponsorBlock.Segment sg : segments) {
            if (pos >= sg.startMs && pos < sg.endMs - 300) {
                long dur = player.getDuration();
                long target = sg.endMs;
                if (dur > 0 && target >= dur - 500) {
                    // Abschnitt geht bis zum Ende: Video ist praktisch fertig
                    player.seekTo(Math.max(0, dur - 200));
                } else {
                    player.seekTo(target);
                }
                android.widget.Toast.makeText(App.get(), "Übersprungen: " + SponsorBlock.label(sg.category)
                        + " (" + ((sg.endMs - sg.startMs) / 1000) + " s)", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
    }

    /** A–B-Schleife setzen (Zeiten in ms, a < b). */
    public static void setAb(String url, long a, long b) {
        abUrl = url;
        abA = a;
        abB = b;
    }

    public static void clearAb() {
        abUrl = null;
        abA = -1;
        abB = -1;
    }

    public static boolean abActive() {
        return abA >= 0 && abB > abA;
    }

    /** Klang-Einstellungen wurden geändert. */
    public static void applyFx() {
        AudioFx.apply();
    }

    // ------------------------------------------------------------ Warteschlange

    private void saveCurrentPosition(boolean force) {
        if (player == null) return;
        MediaItem cur = player.getCurrentMediaItem();
        long dur = player.getDuration();
        if (cur == null || dur == C.TIME_UNSET || dur <= 0) return;
        long pos = player.getCurrentPosition();
        if (player.getPlaybackState() == Player.STATE_ENDED) pos = dur;
        Positions.save(TubeSources.urlOf(cur), pos, dur, force);
    }

    private void onEnded() {
        saveCurrentPosition(true);
        if (SleepTimer.consumeEndOfVideo()) return; // Schlaf-Timer "am Videoende"
        next(true);
    }

    private void next(boolean automatic) {
        Item it = PlayQueue.next();
        if (it != null) {
            playItem(it, true);
            return;
        }
        if (automatic && !Playback.autoplay()) return;
        // Autoplay: passendes Video aus den Vorschlägen
        MediaItem cur = player != null ? player.getCurrentMediaItem() : null;
        final String url = TubeSources.urlOf(cur);
        if (url == null) return;
        final int token = ++playToken;
        App.IO.execute(() -> {
            try {
                StreamInfo info = App.streamInfo(url);
                List<Item> rel = new ArrayList<>();
                for (InfoItem ii : info.getRelatedItems()) {
                    Item r = Item.from(ii);
                    if (r != null) rel.add(r);
                }
                Item pick = PlayQueue.pickAutoplay(rel);
                if (pick == null) return;
                App.MAIN.post(() -> {
                    if (token != playToken || player == null) return;
                    PlayQueue.append(pick);
                    Item n = PlayQueue.next();
                    if (n != null) playItem(n, true);
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private void previous() {
        if (player != null && player.getCurrentPosition() > 3_000) {
            player.seekTo(0);
            return;
        }
        Item it = PlayQueue.previous();
        if (it != null) playItem(it, true);
        else if (player != null) player.seekTo(0);
    }

    /** Video auflösen und abspielen (im Hintergrund, ohne Bildschirm). */
    private void playItem(Item it, boolean play) {
        if (player == null || it == null || it.url == null) return;
        final int token = ++playToken;
        saveCurrentPosition(true);
        MediaMetadata.Builder md = new MediaMetadata.Builder();
        if (it.title != null) md.setTitle(it.title);
        if (it.uploader != null) md.setArtist(it.uploader);
        final MediaItem request = new MediaItem.Builder()
                .setMediaId(TubeSources.mediaId(it.url, Playback.wantedHeight(this), Playback.audioOnly() || CarLibrary.audioOnly))
                .setMediaMetadata(md.build())
                .build();
        final long start = Positions.resumePosition(it.url);
        App.IO.execute(() -> {
            try {
                MediaItem resolved = TubeSources.resolve(request);
                App.MAIN.post(() -> {
                    if (token != playToken || player == null) return;
                    player.setMediaItem(resolved, start);
                    player.prepare();
                    if (play) player.play();
                });
            } catch (Throwable e) {
                // nicht abspielbar (z. B. privat/gelöscht): nächstes nehmen
                App.MAIN.post(() -> {
                    if (token == playToken && PlayQueue.hasNext()) next(true);
                });
            }
        });
    }

    /**
     * Zeigt Benachrichtigung/Lenkrad/Kopfhörer immer "Vor" und "Zurück" an
     * und leitet sie an die eigene Warteschlange weiter.
     */
    private final class QueuePlayer extends ForwardingPlayer {
        QueuePlayer(Player p) {
            super(p);
        }

        @NonNull
        @Override
        public Commands getAvailableCommands() {
            return super.getAvailableCommands().buildUpon()
                    .addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build();
        }

        @Override
        public boolean isCommandAvailable(int command) {
            return getAvailableCommands().contains(command);
        }

        @Override
        public boolean hasNextMediaItem() {
            return PlayQueue.hasNext() || Playback.autoplay();
        }

        @Override
        public boolean hasPreviousMediaItem() {
            return PlayQueue.hasPrevious();
        }

        @Override
        public void seekToNext() {
            PlaybackService.this.next(false);
        }

        @Override
        public void seekToNextMediaItem() {
            PlaybackService.this.next(false);
        }

        @Override
        public void seekToPrevious() {
            PlaybackService.this.previous();
        }

        @Override
        public void seekToPreviousMediaItem() {
            PlaybackService.this.previous();
        }
    }

    /**
     * Verbindung zu Benachrichtigung, Sperrbildschirm, Bluetooth und Android Auto.
     * Android Auto kann hier durch Später ansehen, Downloads, Playlists, Abos usw. blättern und suchen.
     */
    private final class SessionCallback implements MediaLibrarySession.Callback {
        @NonNull
        @Override
        public ListenableFuture<List<MediaItem>> onAddMediaItems(
                @NonNull MediaSession mediaSession,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems) {
            SettableFuture<List<MediaItem>> future = SettableFuture.create();
            App.IO.execute(() -> {
                try {
                    List<MediaItem> out = new ArrayList<>();
                    for (MediaItem mi : mediaItems) out.add(TubeSources.resolve(CarLibrary.toPlayable(mi)));
                    future.set(out);
                } catch (Throwable e) {
                    future.setException(e);
                }
            });
            return future;
        }

        @NonNull
        @Override
        public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                @NonNull MediaSession mediaSession,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            // Von außen (Android Auto, Google Assistant): ganze Liste in die Warteschlange
            final boolean external = !getPackageName().equals(controller.getPackageName());
            SettableFuture<MediaSession.MediaItemsWithStartPosition> f = SettableFuture.create();
            App.IO.execute(() -> {
                try {
                    if (external && mediaItems.size() == 1) {
                        MediaItem m = mediaItems.get(0);
                        CharSequence q = m.requestMetadata.searchQuery;
                        if (m.mediaId.isEmpty() && q != null && q.length() > 0) {
                            // Sprachbefehl „Spiele … auf DeLorean Tube“
                            CarLibrary.playFromSearch(q.toString());
                        } else {
                            CarLibrary.queueFor(m.mediaId);
                        }
                    }
                    List<MediaItem> out = new ArrayList<>();
                    for (MediaItem mi : mediaItems) out.add(TubeSources.resolve(CarLibrary.toPlayable(mi)));
                    f.set(new MediaSession.MediaItemsWithStartPosition(out,
                            startIndex == C.INDEX_UNSET ? 0 : startIndex, startPositionMs));
                } catch (Throwable e) {
                    f.setException(e);
                }
            });
            return f;
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @Nullable LibraryParams params) {
            return Futures.immediateFuture(LibraryResult.ofItem(CarLibrary.root(), params));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String parentId, int page, int pageSize, @Nullable LibraryParams params) {
            SettableFuture<LibraryResult<ImmutableList<MediaItem>>> f = SettableFuture.create();
            App.IO.execute(() -> {
                try {
                    List<MediaItem> all = CarLibrary.children(parentId);
                    int from = Math.max(0, page * pageSize), to = Math.min(all.size(), from + pageSize);
                    List<MediaItem> part = from < to ? all.subList(from, to) : new ArrayList<>();
                    f.set(LibraryResult.ofItemList(ImmutableList.copyOf(part), params));
                } catch (Throwable e) {
                    f.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO));
                }
            });
            return f;
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String mediaId) {
            MediaItem it = CarLibrary.item(mediaId);
            return Futures.immediateFuture(it != null ? LibraryResult.ofItem(it, null)
                    : LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE));
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<Void>> onSearch(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String query, @Nullable LibraryParams params) {
            App.IO.execute(() -> {
                int n = CarLibrary.search(query);
                App.MAIN.post(() -> session.notifySearchResultChanged(browser, query, n, params));
            });
            return Futures.immediateFuture(LibraryResult.ofVoid());
        }

        @NonNull
        @Override
        public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(
                @NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser,
                @NonNull String query, int page, int pageSize, @Nullable LibraryParams params) {
            List<MediaItem> all = CarLibrary.searchResult(query);
            int from = Math.max(0, page * pageSize), to = Math.min(all.size(), from + pageSize);
            List<MediaItem> part = from < to ? all.subList(from, to) : new ArrayList<>();
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(part), params));
        }
    }
}
