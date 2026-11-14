package de.delorean23.tube;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

import androidx.media3.common.MediaMetadata;

import com.bumptech.glide.Glide;

/**
 * Startbildschirm-Widget: Vorschaubild, Titel, Zurück / Play-Pause / Weiter.
 * Created by DeLorean23
 */
public class TubeWidget extends AppWidgetProvider {

    private static final String ACTION_PLAY = "de.delorean23.tube.WIDGET_PLAY";
    private static final String ACTION_NEXT = "de.delorean23.tube.WIDGET_NEXT";
    private static final String ACTION_PREV = "de.delorean23.tube.WIDGET_PREV";

    private static String lastArt;
    private static Bitmap lastBitmap;
    private static int artToken;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String a = intent.getAction();
        if (a == null) return;
        boolean handled = true;
        switch (a) {
            case ACTION_PLAY:
                handled = PlaybackService.togglePlay();
                break;
            case ACTION_NEXT:
                if (PlaybackService.nowPlaying() != null) PlaybackService.skipNext();
                else handled = false;
                break;
            case ACTION_PREV:
                if (PlaybackService.nowPlaying() != null) PlaybackService.skipPrevious();
                else handled = false;
                break;
            default:
                return;
        }
        if (!handled) {
            // Es läuft nichts: App öffnen
            Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(open);
            } catch (Throwable ignored) {
            }
        }
        update(context);
    }

    private static PendingIntent action(Context c, String action, int code) {
        Intent i = new Intent(c, TubeWidget.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, code, i, flags);
    }

    /** Alle Widgets neu zeichnen (aus dem Player aufgerufen). */
    public static void update(Context context) {
        try {
            Context c = context.getApplicationContext();
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, TubeWidget.class));
            if (ids == null || ids.length == 0) return;
            MediaMetadata md = PlaybackService.nowPlaying();
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            Intent open = new Intent(c, md != null ? PlayerActivity.class : MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            v.setOnClickPendingIntent(R.id.wThumb, PendingIntent.getActivity(c, 40, open, flags));
            v.setOnClickPendingIntent(R.id.wTitle, PendingIntent.getActivity(c, 40, open, flags));
            if (md != null) {
                v.setOnClickPendingIntent(R.id.wPlay, action(c, ACTION_PLAY, 41));
                v.setOnClickPendingIntent(R.id.wNext, action(c, ACTION_NEXT, 42));
                v.setOnClickPendingIntent(R.id.wPrev, action(c, ACTION_PREV, 43));
            } else {
                // nichts geladen: Tasten öffnen die App
                PendingIntent app = PendingIntent.getActivity(c, 40, open, flags);
                v.setOnClickPendingIntent(R.id.wPlay, app);
                v.setOnClickPendingIntent(R.id.wNext, app);
                v.setOnClickPendingIntent(R.id.wPrev, app);
            }
            v.setImageViewResource(R.id.wPlay, PlaybackService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            String art = null;
            if (md != null) {
                v.setTextViewText(R.id.wTitle, md.title != null ? md.title : "DeLorean Tube");
                v.setTextViewText(R.id.wArtist, md.artist != null ? md.artist : "");
                art = md.artworkUri != null ? md.artworkUri.toString() : null;
            } else {
                v.setTextViewText(R.id.wTitle, "DeLorean Tube");
                v.setTextViewText(R.id.wArtist, "Nichts läuft gerade");
            }
            if (art != null && art.equals(lastArt) && lastBitmap != null) {
                v.setImageViewBitmap(R.id.wThumb, lastBitmap);
            } else {
                v.setImageViewResource(R.id.wThumb, R.drawable.ic_launcher);
            }
            m.updateAppWidget(ids, v);
            if (art != null && !art.equals(lastArt)) loadArt(c, art);
        } catch (Throwable ignored) {
        }
    }

    private static void loadArt(Context c, String art) {
        final int token = ++artToken;
        App.IO.execute(() -> {
            try {
                Bitmap b = Glide.with(c).asBitmap().load(art).centerCrop().submit(256, 144).get();
                App.MAIN.post(() -> {
                    if (token != artToken) return;
                    lastArt = art;
                    lastBitmap = b.copy(Bitmap.Config.RGB_565, false);
                    update(c);
                });
            } catch (Throwable ignored) {
            }
        });
    }
}
