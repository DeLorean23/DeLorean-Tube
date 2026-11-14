package de.delorean23.tube;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Download-Auswahl: Audio (MP3), Video (MP4) mit Auflösung, Kapitel als einzelne MP3s,
 * nur ein Abschnitt (von–bis) und ganze Playlists.
 * Created by DeLorean23
 */
public final class DownloadDialog {

    public static final int PERMISSION_REQUEST = 7;

    private DownloadDialog() {
    }

    private static boolean checkPermission(Activity a) {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(a,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            Toast.makeText(a, "Bitte Speichern erlauben und dann nochmal auf Download tippen",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    public static void show(Activity a, StreamInfo info, String url) {
        show(a, info, url, -1);
    }

    /** positionMs: aktuelle Stelle im Video (für "Abschnitt" vorausgefüllt), -1 = unbekannt. */
    public static void show(Activity a, StreamInfo info, String url, long positionMs) {
        if (info == null) {
            Toast.makeText(a, "Video wird noch geladen …", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Streams.isLive(info)) {
            Toast.makeText(a, "Live-Streams können nicht heruntergeladen werden", Toast.LENGTH_LONG).show();
            return;
        }
        if (!checkPermission(a)) return;
        int chapters = info.getStreamSegments() != null ? info.getStreamSegments().size() : 0;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Audio (MP3)");
        actions.add(() -> start(a, info, url, true, 0, DownloadService.MODE_FULL, 0, 0));
        labels.add("Video (MP4)");
        actions.add(() -> askResolution(a, info, url, DownloadService.MODE_FULL, 0, 0));
        if (chapters >= 2) {
            labels.add("Kapitel als einzelne MP3s (" + chapters + " Teile)");
            actions.add(() -> start(a, info, url, true, 0, DownloadService.MODE_CHAPTERS, 0, 0));
        }
        labels.add("Nur einen Abschnitt (von–bis) …");
        actions.add(() -> askSection(a, info, url, positionMs));
        new AlertDialog.Builder(a)
                .setTitle("Download")
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void askResolution(Activity a, StreamInfo info, String url, String mode, long s, long e) {
        LinkedHashMap<Integer, VideoStream> choices = Streams.videoChoices(info, true);
        if (choices.isEmpty()) {
            Toast.makeText(a, "Für dieses Video gibt es keine MP4-Datei", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Integer> heights = new ArrayList<>(choices.keySet());
        String[] labels = new String[heights.size()];
        long dur = info.getDuration();
        if (DownloadService.MODE_SECTION.equals(mode)) dur = Math.max(1, (e - s) / 1000);
        for (int i = 0; i < heights.size(); i++) {
            int h = heights.get(i);
            VideoStream v = choices.get(h);
            String size = "";
            if (v != null && v.getBitrate() > 0 && dur > 0) {
                long bytes = (long) v.getBitrate() / 8 * dur + 128_000L / 8 * dur;
                size = "  (ca. " + (bytes / (1024 * 1024)) + " MB)";
            }
            labels[i] = Streams.label(v) + size;
        }
        new AlertDialog.Builder(a)
                .setTitle("Welche Auflösung?")
                .setItems(labels, (d, which) -> start(a, info, url, false, heights.get(which), mode, s, e))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /** Abschnitt wählen: von/bis als m:ss oder h:mm:ss, dazu MP3 oder MP4. */
    private static void askSection(Activity a, StreamInfo info, String url, long positionMs) {
        int p = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p / 2, p, 0);
        long dur = info.getDuration();
        long from = positionMs > 0 ? positionMs / 1000 : 0;
        long to = Math.min(dur > 0 ? dur : from + 30, from + 30);
        EditText eFrom = timeField(a, "Von", from);
        EditText eTo = timeField(a, "Bis", to);
        box.addView(label(a, "Von (z. B. 1:05)"));
        box.addView(eFrom);
        box.addView(label(a, "Bis"));
        box.addView(eTo);
        RadioGroup rg = new RadioGroup(a);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rMp3 = new RadioButton(a);
        rMp3.setText("MP3 (z. B. Klingelton)");
        rMp3.setId(android.view.View.generateViewId());
        RadioButton rMp4 = new RadioButton(a);
        rMp4.setText("MP4");
        rMp4.setId(android.view.View.generateViewId());
        rg.addView(rMp3);
        rg.addView(rMp4);
        rg.check(rMp3.getId());
        box.addView(rg);
        TextView hint = label(a, "Länge des Videos: " + Util.duration(dur)
                + ". MP4 beginnt am nächsten Schlüsselbild davor.");
        box.addView(hint);
        new AlertDialog.Builder(a)
                .setTitle("Abschnitt herunterladen")
                .setView(box)
                .setPositiveButton("Herunterladen", (d, w) -> {
                    long s = parse(eFrom.getText().toString());
                    long e = parse(eTo.getText().toString());
                    if (s < 0 || e <= s || (dur > 0 && s >= dur)) {
                        Toast.makeText(a, "Bitte gültige Zeiten eingeben (Bis muss nach Von liegen)",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (dur > 0) e = Math.min(e, dur);
                    long sMs = s * 1000, eMs = e * 1000;
                    if (rg.getCheckedRadioButtonId() == rMp3.getId()) {
                        start(a, info, url, true, 0, DownloadService.MODE_SECTION, sMs, eMs);
                    } else {
                        askResolution(a, info, url, DownloadService.MODE_SECTION, sMs, eMs);
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static TextView label(Activity a, String t) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextColor(a.getResources().getColor(R.color.text_dim));
        tv.setTextSize(13);
        tv.setPadding(0, (int) (8 * a.getResources().getDisplayMetrics().density), 0, 0);
        return tv;
    }

    private static EditText timeField(Activity a, String hint, long seconds) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        e.setText(Util.duration(Math.max(0, seconds)).isEmpty() ? "0:00" : Util.duration(seconds));
        e.setGravity(Gravity.START);
        return e;
    }

    /** "1:05" / "1:02:03" / "65" -> Sekunden, -1 = ungültig */
    static long parse(String s) {
        if (s == null) return -1;
        s = s.trim().replace('.', ':').replace(',', ':');
        if (s.isEmpty()) return -1;
        String[] parts = s.split(":");
        long total = 0;
        try {
            for (String p : parts) total = total * 60 + Long.parseLong(p.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        return total;
    }

    private static void start(Activity a, StreamInfo info, String url, boolean mp3, int height,
                              String mode, long startMs, long endMs) {
        Intent i = new Intent(a, DownloadService.class)
                .putExtra("url", url)
                .putExtra("title", info.getName())
                .putExtra("artist", info.getUploaderName())
                .putExtra("mp3", mp3)
                .putExtra("height", height)
                .putExtra("mode", mode)
                .putExtra("startMs", startMs)
                .putExtra("endMs", endMs);
        ContextCompat.startForegroundService(a, i);
        String what = DownloadService.MODE_CHAPTERS.equals(mode) ? "Kapitel-Download gestartet"
                : DownloadService.MODE_SECTION.equals(mode) ? "Abschnitt-Download gestartet"
                : mp3 ? "MP3-Download gestartet" : "Video-Download gestartet";
        Toast.makeText(a, what, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ ganze Playlist

    private static final int[] PL_HEIGHTS = {1080, 720, 480, 360};

    /** Alle Videos einer Liste herunterladen (in einen eigenen Ordner, mit Titelnummern). */
    public static void playlist(Activity a, List<Item> items, String name) {
        final List<Item> videos = new ArrayList<>();
        for (Item it : items) if (it.type == Item.VIDEO && it.url != null && !it.url.startsWith("local:")) videos.add(it);
        if (videos.isEmpty()) {
            Toast.makeText(a, "Keine Videos zum Herunterladen", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!checkPermission(a)) return;
        final String folder = name != null && !name.isEmpty() ? name : "Playlist";
        String[] labels = {"Alle als MP3 (" + videos.size() + ")", "Alle als MP4 (" + videos.size() + ")"};
        new AlertDialog.Builder(a)
                .setTitle("„" + folder + "“ herunterladen")
                .setItems(labels, (d, w) -> {
                    if (w == 0) {
                        enqueue(a, videos, folder, true, 0);
                    } else {
                        String[] q = {"1080p (oder beste darunter)", "720p (oder beste darunter)",
                                "480p (oder beste darunter)", "360p"};
                        new AlertDialog.Builder(a)
                                .setTitle("Qualität für alle Videos")
                                .setItems(q, (d2, w2) -> enqueue(a, videos, folder, false, PL_HEIGHTS[w2]))
                                .setNegativeButton("Abbrechen", null)
                                .show();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void enqueue(Activity a, List<Item> videos, String folder, boolean mp3, int height) {
        for (int n = 0; n < videos.size(); n++) {
            Item it = videos.get(n);
            Intent i = new Intent(a, DownloadService.class)
                    .putExtra("url", it.url)
                    .putExtra("title", it.title)
                    .putExtra("artist", it.uploader)
                    .putExtra("mp3", mp3)
                    .putExtra("height", height)
                    .putExtra("mode", DownloadService.MODE_FULL)
                    .putExtra("folder", folder)
                    .putExtra("album", folder)
                    .putExtra("track", n + 1);
            ContextCompat.startForegroundService(a, i);
        }
        Toast.makeText(a, videos.size() + " Downloads gestartet – Ordner „" + folder + "“", Toast.LENGTH_LONG).show();
    }
}
