package de.delorean23.tube;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Menü beim langen Drücken auf ein Video.
 * Created by DeLorean23
 */
public final class VideoMenu {

    /** Zusätzlicher Eintrag (z. B. "Aus Playlist entfernen"). */
    public static final class Extra {
        final String label;
        final Runnable action;

        public Extra(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }
    }

    private VideoMenu() {
    }

    public static void show(Activity a, Item it, Extra... extras) {
        if (it == null || it.type != Item.VIDEO || it.url == null) return;
        final boolean inWl = LocalPlaylists.contains(LocalPlaylists.WATCH_LATER, it.url);
        final boolean playing = PlayQueue.current() != null;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(playing ? "Als Nächstes abspielen" : "Abspielen");
        actions.add(() -> {
            if (!playing) {
                open(a, it);
                return;
            }
            PlayQueue.playNext(it);
            Toast.makeText(a, "Kommt als Nächstes", Toast.LENGTH_SHORT).show();
        });
        if (playing) {
            labels.add("Zur Warteschlange hinzufügen");
            actions.add(() -> {
                PlayQueue.append(it);
                Toast.makeText(a, "Zur Warteschlange hinzugefügt", Toast.LENGTH_SHORT).show();
            });
        }
        labels.add(inWl ? "Aus „Später ansehen“ entfernen" : "Später ansehen");
        actions.add(() -> {
            if (inWl) LocalPlaylists.remove(LocalPlaylists.WATCH_LATER, it.url);
            else LocalPlaylists.add(LocalPlaylists.WATCH_LATER, it);
            Toast.makeText(a, inWl ? "Aus „Später ansehen“ entfernt" : "In „Später ansehen“ gespeichert",
                    Toast.LENGTH_SHORT).show();
        });
        labels.add("In Playlist speichern …");
        actions.add(() -> PlaylistDialogs.save(a, it));
        labels.add("Herunterladen …");
        actions.add(() -> download(a, it));
        if (it.url != null && !it.url.startsWith("local:")) {
            labels.add("Wecker mit diesem Video …");
            actions.add(() -> a.startActivity(AlarmsActivity.forVideo(a, it.url, it.title)));
        }
        labels.add("Teilen");
        actions.add(() -> a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.url), "Teilen")));
        for (Extra e : extras) {
            if (e == null) continue;
            labels.add(e.label);
            actions.add(e.action);
        }

        new AlertDialog.Builder(a)
                .setTitle(it.title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private static void open(Activity a, Item it) {
        PlayQueue.playSingle(it);
        a.startActivity(PlayerActivity.intent(a, it.url, it));
    }

    /** Infos laden, dann das normale Download-Fenster zeigen. */
    public static void download(Activity a, Item it) {
        Toast.makeText(a, "Wird vorbereitet …", Toast.LENGTH_SHORT).show();
        App.IO.execute(() -> {
            try {
                org.schabi.newpipe.extractor.stream.StreamInfo info = App.streamInfo(it.url);
                App.MAIN.post(() -> {
                    if (!a.isFinishing()) DownloadDialog.show(a, info, it.url);
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> Toast.makeText(a, "Nicht möglich: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }
}
