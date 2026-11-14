package de.delorean23.tube;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.common.util.concurrent.ListenableFuture;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Videoseite mit Player, Like, Abo, Download, Chromecast und Hintergrund-Wiedergabe.
 * Video nach unten ziehen (oder Zurück) = Mini-Player.
 * Created by DeLorean23
 */
public class PlayerActivity extends AppCompatActivity {

    public static Intent intent(Context c, String url, Item item) {
        Intent i = new Intent(c, PlayerActivity.class).putExtra("url", url);
        if (item != null) {
            i.putExtra("title", item.title).putExtra("uploader", item.uploader)
                    .putExtra("thumb", item.thumb);
        }
        return i;
    }

    private PlayerView playerView;
    private DragFrame playerBox;
    private View root, loading, header;
    private RecyclerView details;
    private ItemAdapter related;
    private TextView vTitle, vMeta, vDesc, chName, chSubs, btnSubscribe, btnLike, btnAudio, btnTimer, btnSpeed;
    private TextView seekHint, btnDislike, btnAb;
    private long abStart = -1;
    private android.view.GestureDetector gestures;
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (btnTimer != null) btnTimer.setText(SleepTimer.label());
            App.MAIN.postDelayed(this, 20_000);
        }
    };
    private ImageView chAvatar;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private String url;
    private StreamInfo info;
    private String pendingUrl;
    private boolean audioOnly;
    private String rating = "none";
    private String subscriptionId;
    private String channelId;
    private boolean descExpanded;
    private boolean minimizing;
    private String castAskedFor;
    private SessionManagerListener<CastSession> castListener;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        prefs = Playback.prefs();
        audioOnly = Playback.audioOnly();

        root = findViewById(R.id.root);
        playerView = findViewById(R.id.playerView);
        playerBox = findViewById(R.id.playerBox);
        loading = findViewById(R.id.playerLoading);
        details = findViewById(R.id.details);

        // LayoutManager zuerst setzen, sonst stürzt das Aufbauen des Kopfteils ab
        details.setLayoutManager(new LinearLayoutManager(this));
        header = LayoutInflater.from(this).inflate(R.layout.player_header, details, false);
        vTitle = header.findViewById(R.id.vTitle);
        vMeta = header.findViewById(R.id.vMeta);
        vDesc = header.findViewById(R.id.vDesc);
        chName = header.findViewById(R.id.chName);
        chSubs = header.findViewById(R.id.chSubs);
        chAvatar = header.findViewById(R.id.chAvatar);
        btnSubscribe = header.findViewById(R.id.btnSubscribe);
        btnLike = header.findViewById(R.id.btnLike);
        btnAudio = header.findViewById(R.id.btnAudio);

        related = new ItemAdapter(this, it -> {
            if (it.type == Item.VIDEO) {
                PlayQueue.playSingle(it);
                openVideo(it.url, it);
            }
            else if (it.type == Item.CHANNEL)
                startActivity(ListActivity.intent(this, ListActivity.MODE_CHANNEL, it.url, it.title));
            else startActivity(ListActivity.intent(this, ListActivity.MODE_PLAYLIST, it.url, it.title));
        });
        related.setCompact(true);
        related.setHeader(header);
        details.setAdapter(related);

        btnLike.setOnClickListener(v -> toggleLike());
        btnSubscribe.setOnClickListener(v -> toggleSubscribe());
        header.findViewById(R.id.btnDownload).setOnClickListener(v -> DownloadDialog.show(this, info, url,
                controller != null ? controller.getCurrentPosition() : -1));
        header.findViewById(R.id.btnQuality).setOnClickListener(v -> askQuality());
        header.findViewById(R.id.btnShare).setOnClickListener(v -> share());
        header.findViewById(R.id.btnCast).setOnClickListener(v -> castNow());
        header.findViewById(R.id.btnSave).setOnClickListener(v -> PlaylistDialogs.save(this, currentItem()));
        btnDislike = header.findViewById(R.id.btnDislike);
        btnDislike.setOnClickListener(v -> toggleDislike());
        btnAb = header.findViewById(R.id.btnAb);
        btnAb.setOnClickListener(v -> abStep());
        header.findViewById(R.id.btnEq).setOnClickListener(v ->
                startActivity(new Intent(this, EqualizerActivity.class)));
        header.findViewById(R.id.btnSubs).setOnClickListener(v -> askSubtitles());
        btnSpeed = header.findViewById(R.id.btnSpeed);
        btnSpeed.setOnClickListener(v -> askSpeed());
        header.findViewById(R.id.btnQueue).setOnClickListener(v -> QueueDialog.show(this));
        btnTimer = header.findViewById(R.id.btnTimer);
        btnTimer.setOnClickListener(v -> askTimer());
        header.findViewById(R.id.btnComments).setOnClickListener(v -> {
            if (url != null) CommentsDialog.show(this, url);
        });
        related.setOnLongClick(it -> VideoMenu.show(this, it));
        btnAudio.setOnClickListener(v -> toggleAudioOnly());
        header.findViewById(R.id.channelRow).setOnClickListener(v -> {
            if (info != null && info.getUploaderUrl() != null) {
                startActivity(ListActivity.intent(this, ListActivity.MODE_CHANNEL,
                        info.getUploaderUrl(), info.getUploaderName()));
            }
        });
        vDesc.setOnClickListener(v -> {
            descExpanded = !descExpanded;
            vDesc.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
        });
        vDesc.setMovementMethod(LinkMovementMethod.getInstance());

        playerView.setFullscreenButtonClickListener(full -> {
            if (Tv.isTv(this)) {
                // Fernseher: Knopf blendet Infos und Knöpfe unter dem Video ein/aus
                tvDetails = !full;
                applyOrientation(getResources().getConfiguration());
                return;
            }
            setOrientation(full
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        });
        findViewById(R.id.btnMinimize).setOnClickListener(v -> minimize());
        Caster.setupButton(this, findViewById(R.id.castButton));
        setupDrag();
        setupDoubleTap();

        updateAudioButton();
        applyOrientation(getResources().getConfiguration());
        handleIntent(getIntent());
        connect();
    }

    /** Android 8.0 erlaubt bei durchsichtigen Fenstern keine feste Ausrichtung. */
    private void setOrientation(int o) {
        if (Build.VERSION.SDK_INT == 26) return;
        try {
            setRequestedOrientation(o);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        String u = i.getStringExtra("url");
        if (u == null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            u = i.getData().toString();
        }
        if (u == null && Intent.ACTION_SEND.equals(i.getAction())) {
            u = Util.findYoutubeUrl(i.getStringExtra(Intent.EXTRA_TEXT));
            if (u == null) {
                Toast.makeText(this, "Kein YouTube-Link gefunden", Toast.LENGTH_LONG).show();
                if (info == null) finish();
                return;
            }
        }
        if (u == null) return; // aus Mini-Player/Benachrichtigung: aktuelles Video zeigen
        String id = Util.videoId(u);
        if (id != null) u = Util.watchUrl(id);
        i.removeExtra("url");

        String t = i.getStringExtra("title");
        if (!i.getBooleanExtra("queue", false)) {
            // einzelnes Video (Link, Suche …): Warteschlange beginnt mit diesem Video
            Item single = new Item();
            single.url = u;
            single.title = t;
            single.uploader = i.getStringExtra("uploader");
            single.thumb = i.getStringExtra("thumb");
            PlayQueue.ensureCurrent(single);
        }
        i.removeExtra("queue");
        vTitle.setText(t != null ? t : "Wird geladen …");
        chName.setText(t != null ? i.getStringExtra("uploader") : "");
        vMeta.setText("");
        chSubs.setText("");
        chAvatar.setImageDrawable(null);
        openVideo(u, null);
    }

    private void openVideo(String u, Item it) {
        if (!u.equals(url)) {
            PlaybackService.clearAb();
            resetAbButton();
        }
        if (it != null) {
            vTitle.setText(it.title);
            chName.setText(it.uploader);
        }
        url = u;
        info = null;
        rating = "none";
        subscriptionId = null;
        channelId = null;
        descExpanded = false;
        vDesc.setMaxLines(3);
        vDesc.setVisibility(View.GONE);
        related.set(null);
        details.scrollToPosition(0);
        if (controller != null) {
            if (!isCurrent(u)) startPlayback(u, Positions.resumePosition(u));
        } else {
            pendingUrl = u;
        }
        loadInfo(u);
    }

    /** Läuft genau dieses Video schon (z. B. aus dem Mini-Player wieder groß gemacht)? */
    private boolean isCurrent(String u) {
        if (controller == null) return false;
        MediaItem cur = controller.getCurrentMediaItem();
        String cu = TubeSources.urlOf(cur);
        return cu != null && u != null && cu.equals(u) && controller.getPlaybackState() != Player.STATE_IDLE;
    }

    // ------------------------------------------------------------ Player

    private void connect() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Throwable e) {
                Toast.makeText(this, "Player nicht verfügbar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            if (!isFinishing() && hasWindowFocusOrResumed()) playerView.setPlayer(controller);
            controller.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    loading.setVisibility(View.GONE);
                    Toast.makeText(PlayerActivity.this, "Wiedergabefehler: "
                            + error.getMessage(), Toast.LENGTH_LONG).show();
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state != Player.STATE_IDLE) loading.setVisibility(View.GONE);
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    updatePipActions();
                }

                @Override
                public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                    // Warteschlange/Autoplay hat ein anderes Video gestartet: Seite umstellen
                    String u = TubeSources.urlOf(mediaItem);
                    if (u != null && !u.equals(url)) showOther(u, mediaItem);
                }

                @Override
                public void onPlaybackParametersChanged(@NonNull androidx.media3.common.PlaybackParameters p) {
                    updateSpeedButton();
                }
            });
            updateSpeedButton();
            Playback.setVideo(controller, true);
            if (pendingUrl != null) {
                String u = pendingUrl;
                pendingUrl = null;
                if (!isCurrent(u)) startPlayback(u, Positions.resumePosition(u));
            } else if (url == null) {
                // aus Mini-Player/Benachrichtigung: laufendes Video anzeigen
                MediaItem cur = controller.getCurrentMediaItem();
                if (cur != null) {
                    url = TubeSources.urlOf(cur);
                    CharSequence t = controller.getMediaMetadata().title;
                    vTitle.setText(t != null ? t : "Wird geladen …");
                    loadInfo(url);
                } else {
                    finish();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean resumed;

    private boolean hasWindowFocusOrResumed() {
        return resumed;
    }

    private int wantedHeight() {
        boolean wifi = Util.isOnWifi(this);
        return prefs.getInt(wifi ? "height_wifi" : "height_mobile", wifi ? 720 : 480);
    }

    private void startPlayback(String u, long positionMs) {
        if (controller == null) return;
        loading.setVisibility(View.VISIBLE);
        MediaMetadata.Builder md = new MediaMetadata.Builder();
        CharSequence t = vTitle.getText();
        if (t != null && t.length() > 0) md.setTitle(t);
        MediaItem item = new MediaItem.Builder()
                .setMediaId(TubeSources.mediaId(u, wantedHeight(), audioOnly))
                .setMediaMetadata(md.build())
                .build();
        controller.setMediaItem(item, positionMs);
        controller.prepare();
        if (Caster.connected(this)) {
            // läuft schon auf dem Fernseher: nicht zusätzlich am Handy abspielen
            controller.pause();
        } else {
            controller.play();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (controller != null) Playback.setVideo(controller, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (controller != null) playerView.setPlayer(controller);
        App.MAIN.removeCallbacks(timerTick);
        App.MAIN.post(timerTick);
        CastContext cc = Caster.ctx(this);
        if (cc != null) {
            castListener = new CastListener();
            cc.getSessionManager().addSessionManagerListener(castListener, CastSession.class);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        App.MAIN.removeCallbacks(timerTick);
        // Bild an den Mini-Player abgeben (nicht im Bild-im-Bild-Fenster)
        if (!inPip()) playerView.setPlayer(null);
        CastContext cc = Caster.ctx(this);
        if (cc != null && castListener != null) {
            cc.getSessionManager().removeSessionManagerListener(castListener, CastSession.class);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // App im Hintergrund oder Bildschirm aus: Ton läuft weiter, Bild wird nicht mehr geladen
        if (controller != null && App.visibleScreens() == 0) Playback.setVideo(controller, false);
    }

    @Override
    protected void onDestroy() {
        App.MAIN.removeCallbacks(timerTick);
        if (pipReceiver != null) {
            try {
                unregisterReceiver(pipReceiver);
            } catch (Throwable ignored) {
            }
        }
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (inPip()) return;
        applyOrientation(newConfig);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Fernseher: Infos unter dem Video zeigen (statt Vollbild). */
    private boolean tvDetails;

    private void applyOrientation(Configuration c) {
        boolean tv = Tv.isTv(this);
        boolean land = tv ? !tvDetails : c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) playerBox.getLayoutParams();
        if (land) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.weight = 1;
            details.setVisibility(View.GONE);
            playerView.setFullscreenButtonState(true);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            int w = getResources().getDisplayMetrics().widthPixels;
            lp.height = w * 9 / 16;
            if (tv) lp.height = Math.min(lp.height, getResources().getDisplayMetrics().heightPixels / 2);
            lp.weight = 0;
            details.setVisibility(View.VISIBLE);
            playerView.setFullscreenButtonState(false);
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        playerBox.setLayoutParams(lp);
        playerBox.setDragEnabled(!land && !tv);
        if (tv && land) playerView.requestFocus();
    }

    /** Fernbedienung: links/rechts = 10 s zurück/vor, solange die Steuerleiste nicht offen ist. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (Tv.isTv(this) && !tvDetails && controller != null
                && e.getAction() == android.view.KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible()) {
            int k = e.getKeyCode();
            if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                controller.seekTo(Math.max(0, controller.getCurrentPosition() - 10_000));
                return true;
            }
            if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                controller.seekTo(controller.getCurrentPosition() + 10_000);
                return true;
            }
            if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN || k == android.view.KeyEvent.KEYCODE_MENU) {
                playerView.showController();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (Tv.isTv(this)) {
            if (playerView.isControllerFullyVisible()) {
                playerView.hideController();
                return;
            }
            if (tvDetails) {
                tvDetails = false;
                applyOrientation(getResources().getConfiguration());
                return;
            }
            minimize();
            return;
        }
        if (isLandscape()) {
            setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            playerView.postDelayed(() ->
                    setOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), 1500);
            return;
        }
        minimize();
    }

    // ------------------------------------------------------------ Verkleinern (Mini-Player)

    private void setupDrag() {
        playerBox.setListener(new DragFrame.Listener() {
            @Override
            public void onDrag(float dy) {
                applyDrag(dy);
            }

            @Override
            public void onRelease(float dy, float velocityY) {
                float h = root.getHeight();
                if (dy > h * 0.2f || velocityY > 1500) {
                    minimize();
                } else {
                    root.animate().translationY(0).setDuration(180).start();
                    playerBox.animate().scaleX(1).scaleY(1).translationX(0).setDuration(180).start();
                    details.animate().alpha(1).setDuration(180).start();
                    setBackgroundAlpha(255);
                }
            }
        });
    }

    private void applyDrag(float dy) {
        float h = Math.max(1, root.getHeight());
        float p = Math.min(1f, dy / (h * 0.6f));
        root.setTranslationY(dy);
        playerBox.setPivotX(playerBox.getWidth());
        playerBox.setPivotY(0);
        float s = 1f - 0.5f * p;
        playerBox.setScaleX(s);
        playerBox.setScaleY(s);
        details.setAlpha(Math.max(0f, 1f - p * 2f));
        setBackgroundAlpha((int) (255 * (1f - p)));
    }

    private void setBackgroundAlpha(int a) {
        Drawable bg = root.getBackground();
        if (bg != null) bg.mutate().setAlpha(Math.max(0, Math.min(255, a)));
    }

    /** Video schrumpft nach unten rechts, die Seite darunter wird sichtbar, Ton/Bild laufen weiter. */
    private void minimize() {
        if (minimizing) return;
        minimizing = true;
        if (isTaskRoot()) {
            // per Link geöffnet: Startseite darunter öffnen, damit der Mini-Player dort weiterläuft
            startActivity(new Intent(this, MainActivity.class));
        }
        float density = getResources().getDisplayMetrics().density;
        float miniW = 200 * density, miniH = 113 * density, margin = 12 * density, bottomBar = 56 * density;
        float boxW = Math.max(1, playerBox.getWidth());
        float s = miniW / boxW;
        float targetTop = root.getHeight() - bottomBar - margin - miniH;
        playerBox.setPivotX(playerBox.getWidth());
        playerBox.setPivotY(0);
        DecelerateInterpolator di = new DecelerateInterpolator();
        details.animate().alpha(0).setDuration(150).start();
        setBackgroundAlpha(0);
        playerBox.animate().scaleX(s).scaleY(s).translationX(-margin).setInterpolator(di).setDuration(220).start();
        root.animate().translationY(targetTop).setInterpolator(di).setDuration(220)
                .withEndAction(() -> {
                    finish();
                    overridePendingTransition(0, R.anim.fade_out);
                }).start();
    }

    // ------------------------------------------------------------ Chromecast

    private void castNow() {
        long pos = controller != null ? controller.getCurrentPosition() : 0;
        Caster.cast(this, info, url, pos, () -> {
            castAskedFor = url;
            if (controller != null) controller.pause();
        });
    }

    /** Neues Video, während der Fernseher verbunden ist: einmal fragen. */
    private void maybeAskCast() {
        if (info != null && url != null && !url.equals(castAskedFor) && Caster.connected(this)) {
            castAskedFor = url;
            castNow();
        }
    }

    private final class CastListener implements SessionManagerListener<CastSession> {
        @Override public void onSessionStarted(@NonNull CastSession s, @NonNull String id) {
            castAskedFor = null;
            maybeAskCast();
        }
        @Override public void onSessionResumed(@NonNull CastSession s, boolean wasSuspended) { }
        @Override public void onSessionStarting(@NonNull CastSession s) { }
        @Override public void onSessionStartFailed(@NonNull CastSession s, int error) {
            Toast.makeText(PlayerActivity.this, "Verbindung zum Fernseher fehlgeschlagen", Toast.LENGTH_LONG).show();
        }
        @Override public void onSessionEnding(@NonNull CastSession s) { }
        @Override public void onSessionEnded(@NonNull CastSession s, int error) { castAskedFor = null; }
        @Override public void onSessionResuming(@NonNull CastSession s, @NonNull String id) { }
        @Override public void onSessionResumeFailed(@NonNull CastSession s, int error) { }
        @Override public void onSessionSuspended(@NonNull CastSession s, int reason) { }
    }

    // ------------------------------------------------------------ Warteschlange

    /** Aktuelles Video als Listeneintrag (für Speichern, Menüs). */
    private Item currentItem() {
        if (url == null) return null;
        Item it = new Item();
        it.url = url;
        it.title = info != null ? info.getName() : String.valueOf(vTitle.getText());
        it.uploader = info != null ? info.getUploaderName() : null;
        it.uploaderUrl = info != null ? info.getUploaderUrl() : null;
        it.thumb = info != null ? Util.thumb(info.getThumbnails()) : null;
        it.duration = info != null ? info.getDuration() : -1;
        return it;
    }

    /** Ein anderes Video läuft jetzt (aus der Warteschlange): Infos dazu zeigen, ohne neu zu starten. */
    private void showOther(String u, MediaItem mi) {
        resetAbButton();
        url = u;
        info = null;
        rating = "none";
        subscriptionId = null;
        channelId = null;
        descExpanded = false;
        vDesc.setMaxLines(3);
        vDesc.setVisibility(View.GONE);
        CharSequence t = mi != null ? mi.mediaMetadata.title : null;
        vTitle.setText(t != null ? t : "Wird geladen …");
        vMeta.setText("");
        chSubs.setText("");
        related.set(null);
        details.scrollToPosition(0);
        loadInfo(u);
    }

    // ------------------------------------------------------------ Doppeltippen = spulen

    private void setupDoubleTap() {
        seekHint = new TextView(this);
        seekHint.setTextColor(0xFFFFFFFF);
        seekHint.setTextSize(16);
        seekHint.setBackgroundResource(R.drawable.circle_dark);
        seekHint.setGravity(android.view.Gravity.CENTER);
        seekHint.setVisibility(View.GONE);
        int sz = (int) (88 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(sz, sz,
                android.view.Gravity.CENTER_VERTICAL);
        playerBox.addView(seekHint, lp);
        gestures = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(android.view.MotionEvent e) {
                if (controller == null) return false;
                boolean forward = e.getX() > playerView.getWidth() / 2f;
                long pos = controller.getCurrentPosition() + (forward ? 10_000 : -10_000);
                long dur = controller.getDuration();
                if (dur > 0) pos = Math.min(pos, dur - 500);
                controller.seekTo(Math.max(0, pos));
                showSeekHint(forward);
                return true;
            }
        });
        playerView.setOnTouchListener((v, e) -> {
            gestures.onTouchEvent(e);
            return false; // normales Antippen (Steuerung zeigen) bleibt
        });
    }

    private void showSeekHint(boolean forward) {
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) seekHint.getLayoutParams();
        lp.gravity = android.view.Gravity.CENTER_VERTICAL | (forward ? android.view.Gravity.END : android.view.Gravity.START);
        int m = (int) (32 * getResources().getDisplayMetrics().density);
        lp.leftMargin = m;
        lp.rightMargin = m;
        seekHint.setLayoutParams(lp);
        seekHint.setText(forward ? "+10 s ▶▶" : "◀◀ −10 s");
        seekHint.setVisibility(View.VISIBLE);
        seekHint.setAlpha(1f);
        seekHint.animate().cancel();
        seekHint.animate().alpha(0f).setStartDelay(500).setDuration(300)
                .withEndAction(() -> seekHint.setVisibility(View.GONE)).start();
        playerView.hideController();
    }

    // ------------------------------------------------------------ Tempo / Untertitel / Timer

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private void updateSpeedButton() {
        if (btnSpeed == null || controller == null) return;
        float sp = controller.getPlaybackParameters().speed;
        btnSpeed.setText(sp == 1f ? "Tempo" : speedLabel(sp));
    }

    private static String speedLabel(float s) {
        String t = (s == (int) s) ? String.valueOf((int) s) : String.valueOf(s).replace('.', ',');
        return t + "×";
    }

    private void askSpeed() {
        if (controller == null) return;
        float cur = controller.getPlaybackParameters().speed;
        String[] labels = new String[SPEEDS.length];
        int checked = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = SPEEDS[i] == 1f ? "Normal" : speedLabel(SPEEDS[i]);
            if (Math.abs(SPEEDS[i] - cur) < 0.01f) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Wiedergabegeschwindigkeit")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    controller.setPlaybackSpeed(SPEEDS[w]);
                    d.dismiss();
                })
                .show();
    }

    private void askSubtitles() {
        if (info == null) {
            Toast.makeText(this, "Video wird noch geladen …", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<TubeSources.SubOption> opts = TubeSources.subtitleOptions(info);
        if (opts.size() <= 1) {
            Toast.makeText(this, "Für dieses Video gibt es keine Untertitel", Toast.LENGTH_SHORT).show();
            return;
        }
        String cur = Playback.subtitles();
        String[] labels = new String[opts.size()];
        int checked = 0;
        for (int i = 0; i < opts.size(); i++) {
            labels[i] = opts.get(i).label;
            if (opts.get(i).code.equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Untertitel")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    prefs.edit().putString("subtitles", opts.get(w).code).apply();
                    d.dismiss();
                    restartAtCurrentPosition();
                })
                .show();
    }

    private void askTimer() {
        String[] labels = {"Aus", "15 Minuten", "30 Minuten", "45 Minuten", "60 Minuten", "90 Minuten",
                "Am Ende des Videos"};
        int[] minutes = {0, 15, 30, 45, 60, 90, -1};
        new AlertDialog.Builder(this)
                .setTitle("Schlaf-Timer" + (SleepTimer.active() ? " (läuft: " + SleepTimer.label() + ")" : ""))
                .setItems(labels, (d, w) -> {
                    if (minutes[w] == 0) SleepTimer.cancel();
                    else if (minutes[w] < 0) SleepTimer.atEndOfVideo();
                    else SleepTimer.start(minutes[w]);
                    btnTimer.setText(SleepTimer.label());
                    if (minutes[w] != 0) {
                        Toast.makeText(this, minutes[w] < 0 ? "Stoppt am Ende des Videos"
                                : "Stoppt in " + minutes[w] + " Minuten", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ Bild-im-Bild

    private boolean inPip() {
        return Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode();
    }

    private boolean canPip() {
        return Build.VERSION.SDK_INT >= 26 && Playback.pip() && !audioOnly && controller != null
                && controller.isPlaying() && !minimizing
                && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (canPip()) enterPip();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            enterPictureInPictureMode(pipParams());
        } catch (Throwable ignored) {
            // Gerät/Fenster erlaubt kein Bild-im-Bild: Ton läuft im Hintergrund weiter
        }
    }

    @androidx.annotation.RequiresApi(26)
    private android.app.PictureInPictureParams pipParams() {
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(new android.util.Rational(16, 9));
        java.util.ArrayList<android.app.RemoteAction> actions = new java.util.ArrayList<>();
        boolean playing = controller != null && controller.isPlaying();
        android.content.Intent i = new android.content.Intent(PIP_ACTION).setPackage(getPackageName());
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(this, 7, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        actions.add(new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this,
                playing ? R.drawable.ic_pause : R.drawable.ic_play),
                playing ? "Pause" : "Abspielen", playing ? "Pause" : "Abspielen", pi));
        b.setActions(actions);
        return b.build();
    }

    private static final String PIP_ACTION = "de.delorean23.tube.PIP_PLAY_PAUSE";
    private android.content.BroadcastReceiver pipReceiver;

    private void updatePipActions() {
        if (Build.VERSION.SDK_INT >= 26 && inPip()) {
            try {
                setPictureInPictureParams(pipParams());
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        if (isInPip) {
            details.setVisibility(View.GONE);
            playerView.setUseController(false);
            findViewById(R.id.btnMinimize).setVisibility(View.GONE);
            findViewById(R.id.castButton).setVisibility(View.GONE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) playerBox.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.weight = 1;
            playerBox.setLayoutParams(lp);
            pipReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    if (controller == null) return;
                    if (controller.isPlaying()) controller.pause();
                    else controller.play();
                }
            };
            ContextCompat.registerReceiver(this, pipReceiver, new android.content.IntentFilter(PIP_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } else {
            if (pipReceiver != null) {
                try {
                    unregisterReceiver(pipReceiver);
                } catch (Throwable ignored) {
                }
                pipReceiver = null;
            }
            playerView.setUseController(true);
            findViewById(R.id.btnMinimize).setVisibility(View.VISIBLE);
            findViewById(R.id.castButton).setVisibility(View.VISIBLE);
            applyOrientation(getResources().getConfiguration());
            // Fenster mit X geschlossen: anhalten
            if (getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.CREATED
                    && controller != null) {
                controller.pause();
            }
        }
    }

    // ------------------------------------------------------------ Infos

    private void loadInfo(String u) {
        if (u.startsWith("local:")) {
            showLocal(u);
            return;
        }
        setOnlineViews(true);
        App.IO.execute(() -> {
            try {
                StreamInfo si = App.streamInfo(u);
                App.MAIN.post(() -> {
                    if (!u.equals(url) || isFinishing()) return;
                    showInfo(si);
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    if (!u.equals(url)) return;
                    loading.setVisibility(View.GONE);
                    vMeta.setText("Fehler beim Laden: " + e.getMessage());
                });
            }
        });
    }

    private void showInfo(StreamInfo si) {
        info = si;
        vTitle.setText(si.getName());
        StringBuilder meta = new StringBuilder();
        if (Streams.isLive(si)) meta.append("LIVE");
        else if (si.getViewCount() >= 0) meta.append(Util.views(si.getViewCount()));
        if (si.getTextualUploadDate() != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(si.getTextualUploadDate());
        }
        vMeta.setText(meta);
        chName.setText(si.getUploaderName());
        long subs = si.getUploaderSubscriberCount();
        chSubs.setText(subs >= 0 ? Util.count(subs) + " Abonnenten" : "");
        String av = Util.thumb(si.getUploaderAvatars());
        if (av != null) Glide.with(this).load(av).circleCrop().into(chAvatar);
        long likes = si.getLikeCount();
        btnLike.setText(likes >= 0 ? Util.count(likes) : "Mag ich");

        Description d = si.getDescription();
        if (d != null && d.content() != null && !d.content().isEmpty()) {
            vDesc.setVisibility(View.VISIBLE);
            if (d.type() == Description.Type.HTML) {
                vDesc.setText(Build.VERSION.SDK_INT >= 24
                        ? Html.fromHtml(d.content(), Html.FROM_HTML_MODE_LEGACY)
                        : Html.fromHtml(d.content()));
            } else {
                vDesc.setText(d.content());
            }
        } else {
            vDesc.setVisibility(View.GONE);
        }

        List<Item> rel = new ArrayList<>();
        for (InfoItem ii : si.getRelatedItems()) {
            Item it = Item.from(ii);
            if (it != null) rel.add(it);
        }
        related.set(rel);

        Item h = new Item();
        h.url = url;
        h.title = si.getName();
        h.uploader = si.getUploaderName();
        h.uploaderUrl = si.getUploaderUrl();
        h.thumb = Util.thumb(si.getThumbnails());
        h.duration = si.getDuration();
        History.add(h);

        channelId = Util.channelId(si.getUploaderUrl());
        loadDislikes(url);
        updateLike();
        updateSubscribe();
        if (Account.isLoggedIn()) loadAccountState(url);
        maybeAskCast();
    }

    /** Heruntergeladene Datei: keine YouTube-Infos, nur Titel. */
    private void showLocal(String u) {
        Downloads.Entry e = Downloads.find(u.substring(6));
        vTitle.setText(e != null ? e.title : "Heruntergeladene Datei");
        vMeta.setText("Heruntergeladen · läuft offline" + (e != null && e.artist != null ? " · " + e.artist : ""));
        chName.setText(e != null && e.artist != null ? e.artist : "");
        chSubs.setText("");
        vDesc.setVisibility(View.GONE);
        related.set(null);
        loading.setVisibility(View.GONE);
        setOnlineViews(false);
    }

    /** Knöpfe, die nur bei YouTube-Videos Sinn ergeben, ein-/ausblenden. */
    private void setOnlineViews(boolean online) {
        int vis = online ? View.VISIBLE : View.GONE;
        for (int id : new int[]{R.id.channelRow, R.id.btnLike, R.id.btnDislike, R.id.btnSave, R.id.btnDownload, R.id.btnQuality,
                R.id.btnSubs, R.id.btnComments, R.id.btnCast, R.id.btnShare}) {
            View v = header.findViewById(id);
            if (v != null) v.setVisibility(vis);
        }
    }

    // ------------------------------------------------------------ Konto-Aktionen

    private void loadAccountState(String u) {
        final String vid = Util.videoId(u);
        final String ch = channelId;
        App.IO.execute(() -> {
            try {
                String r = vid != null ? Account.getRating(vid) : "none";
                String s = ch != null ? Account.subscriptionId(ch) : null;
                App.MAIN.post(() -> {
                    if (!u.equals(url)) return;
                    rating = r;
                    subscriptionId = s;
                    updateLike();
                    updateSubscribe();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void updateLike() {
        boolean on = "like".equals(rating);
        btnLike.setCompoundDrawablesWithIntrinsicBounds(on ? R.drawable.ic_like_on : R.drawable.ic_like,
                0, 0, 0);
        if (btnDislike != null) {
            btnDislike.setCompoundDrawablesWithIntrinsicBounds("dislike".equals(rating)
                    ? R.drawable.ic_dislike_on : R.drawable.ic_dislike, 0, 0, 0);
        }
    }

    private void loadDislikes(String u) {
        btnDislike.setText("");
        final String vid = Util.videoId(u);
        App.IO.execute(() -> {
            long d = SponsorBlock.dislikes(vid);
            App.MAIN.post(() -> {
                if (u.equals(url) && d >= 0) btnDislike.setText(Util.count(d));
            });
        });
    }

    private void toggleDislike() {
        if (needLogin() || url == null) return;
        final String vid = Util.videoId(url);
        final String old = rating;
        final String newRating = "dislike".equals(rating) ? "none" : "dislike";
        rating = newRating;
        updateLike();
        App.IO.execute(() -> {
            try {
                Account.rate(vid, newRating);
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    rating = old;
                    updateLike();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ------------------------------------------------------------ A–B-Schleife

    /** 1. Tippen = Anfang (A), 2. Tippen = Ende (B), 3. Tippen = aus. */
    private void abStep() {
        if (controller == null || url == null) return;
        long pos = controller.getCurrentPosition();
        if (PlaybackService.abActive()) {
            PlaybackService.clearAb();
            abStart = -1;
            btnAb.setText("A–B");
            styleAb(false);
            Toast.makeText(this, "Schleife aus", Toast.LENGTH_SHORT).show();
        } else if (abStart < 0) {
            abStart = pos;
            btnAb.setText("A " + Util.duration(pos / 1000) + " → B?");
            styleAb(true);
            Toast.makeText(this, "Anfang gesetzt – an der End-Stelle nochmal tippen", Toast.LENGTH_SHORT).show();
        } else {
            if (pos <= abStart + 500) {
                Toast.makeText(this, "Ende muss nach dem Anfang liegen", Toast.LENGTH_SHORT).show();
                return;
            }
            PlaybackService.setAb(url, abStart, pos);
            btnAb.setText(Util.duration(abStart / 1000) + "–" + Util.duration(pos / 1000) + " ⟲");
            controller.seekTo(abStart);
            abStart = -1;
            Toast.makeText(this, "Abschnitt wird wiederholt (nochmal tippen = aus)", Toast.LENGTH_SHORT).show();
        }
    }

    private void styleAb(boolean on) {
        btnAb.setBackgroundResource(on ? R.drawable.chip_on_bg : R.drawable.chip_bg);
        btnAb.setTextColor(getResources().getColor(on ? R.color.black : R.color.text));
    }

    private void resetAbButton() {
        if (btnAb == null) return;
        abStart = -1;
        boolean on = PlaybackService.abActive();
        if (!on) {
            btnAb.setText("A–B");
            styleAb(false);
        }
    }

    private void updateSubscribe() {
        if (subscriptionId != null) {
            btnSubscribe.setText("Abonniert");
            btnSubscribe.setBackgroundResource(R.drawable.chip_bg);
            btnSubscribe.setTextColor(getResources().getColor(R.color.text));
        } else {
            btnSubscribe.setText("Abonnieren");
            btnSubscribe.setBackgroundResource(R.drawable.sub_bg);
            btnSubscribe.setTextColor(getResources().getColor(R.color.black));
        }
    }

    private boolean needLogin() {
        if (Account.isLoggedIn()) return false;
        Toast.makeText(this, "Bitte zuerst auf der Startseite oben rechts mit Google anmelden",
                Toast.LENGTH_LONG).show();
        return true;
    }

    private void toggleLike() {
        if (needLogin() || url == null) return;
        final String vid = Util.videoId(url);
        final String newRating = "like".equals(rating) ? "none" : "like";
        rating = newRating;
        updateLike();
        App.IO.execute(() -> {
            try {
                Account.rate(vid, newRating);
            } catch (Throwable e) {
                App.MAIN.post(() -> {
                    rating = "like".equals(newRating) ? "none" : "like";
                    updateLike();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void toggleSubscribe() {
        if (needLogin() || channelId == null) return;
        final String sub = subscriptionId;
        App.IO.execute(() -> {
            try {
                String id = null;
                if (sub != null) Account.unsubscribe(sub);
                else id = Account.subscribe(channelId);
                final String nid = id;
                App.MAIN.post(() -> {
                    subscriptionId = nid;
                    updateSubscribe();
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void share() {
        if (url == null) return;
        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url);
        if (info != null) s.putExtra(Intent.EXTRA_SUBJECT, info.getName());
        startActivity(Intent.createChooser(s, "Teilen"));
    }

    // ------------------------------------------------------------ Qualität / Nur Ton

    private void updateAudioButton() {
        btnAudio.setCompoundDrawablesWithIntrinsicBounds(
                audioOnly ? R.drawable.ic_headphones_on : R.drawable.ic_headphones, 0, 0, 0);
    }

    private void toggleAudioOnly() {
        audioOnly = !audioOnly;
        prefs.edit().putBoolean("audio_only", audioOnly).apply();
        updateAudioButton();
        Toast.makeText(this, audioOnly ? "Nur Ton (spart Daten)" : "Video an", Toast.LENGTH_SHORT).show();
        restartAtCurrentPosition();
    }

    private void restartAtCurrentPosition() {
        if (controller == null || url == null) return;
        long pos = controller.getCurrentPosition();
        startPlayback(url, pos);
        Playback.setVideo(controller, true);
    }

    private void askQuality() {
        if (info == null) {
            Toast.makeText(this, "Video wird noch geladen …", Toast.LENGTH_SHORT).show();
            return;
        }
        LinkedHashMap<Integer, VideoStream> choices = Streams.videoChoices(info, false);
        if (choices.isEmpty()) {
            Toast.makeText(this, "Keine Auswahl verfügbar", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Integer> heights = new ArrayList<>(choices.keySet());
        String[] labels = new String[heights.size()];
        int current = TubeSources.currentHeight();
        int checked = -1;
        for (int i = 0; i < heights.size(); i++) {
            int h = heights.get(i);
            VideoStream v = choices.get(h);
            labels[i] = Streams.label(v);
            if (h == current) checked = i;
        }
        final boolean wifi = Util.isOnWifi(this);
        new AlertDialog.Builder(this)
                .setTitle("Qualität (" + (wifi ? "WLAN" : "mobile Daten") + ")")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.edit().putInt(wifi ? "height_wifi" : "height_mobile", heights.get(which)).apply();
                    if (audioOnly) {
                        audioOnly = false;
                        prefs.edit().putBoolean("audio_only", false).apply();
                        updateAudioButton();
                    }
                    d.dismiss();
                    restartAtCurrentPosition();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}
