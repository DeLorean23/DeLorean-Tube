package de.delorean23.tube;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

/**
 * Fenster rund um Playlists: "Speichern in …", neue Playlist, umbenennen, löschen.
 * Created by DeLorean23
 */
public final class PlaylistDialogs {

    public interface Done {
        void done();
    }

    private PlaylistDialogs() {
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static TextView section(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(a.getResources().getColor(R.color.text_dim));
        t.setPadding(0, dp(a, 12), 0, dp(a, 4));
        return t;
    }

    // ------------------------------------------------------------ Speichern in …

    /** Häkchen-Liste: Video in "Später ansehen" und beliebige Playlists packen oder rausnehmen. */
    public static void save(Activity a, Item video) {
        if (video == null || video.url == null) return;
        final String videoId = Util.videoId(video.url);
        if (videoId == null) return;

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 4), p, 0);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);

        // Auf dem Gerät
        box.addView(section(a, "AUF DIESEM GERÄT"));
        box.addView(localCheck(a, LocalPlaylists.WATCH_LATER, "Später ansehen", video));
        for (LocalPlaylists.Playlist pl : LocalPlaylists.all()) {
            box.addView(localCheck(a, pl.id, pl.name, video));
        }

        // Google-Konto
        final LinearLayout accountBox = new LinearLayout(a);
        accountBox.setOrientation(LinearLayout.VERTICAL);
        if (Account.isLoggedIn()) {
            box.addView(section(a, "GOOGLE-KONTO"));
            ProgressBar pb = new ProgressBar(a);
            accountBox.addView(pb, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
            box.addView(accountBox);
            App.IO.execute(() -> {
                try {
                    List<Item> pls = Account.myPlaylists();
                    String[] entryIds = new String[pls.size()];
                    // gleichzeitig nachsehen, wo das Video schon drin ist
                    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
                    List<java.util.concurrent.Future<String>> fs = new java.util.ArrayList<>();
                    for (Item pl : pls) fs.add(pool.submit(() -> Account.findInPlaylist(pl.apiPlaylistId, videoId)));
                    for (int i = 0; i < pls.size(); i++) {
                        try {
                            entryIds[i] = fs.get(i).get(20, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                        }
                    }
                    pool.shutdownNow();
                    App.MAIN.post(() -> {
                        accountBox.removeAllViews();
                        if (pls.isEmpty()) {
                            TextView t = new TextView(a);
                            t.setText("Noch keine Playlists im Konto");
                            t.setTextColor(a.getResources().getColor(R.color.text_dim));
                            accountBox.addView(t);
                        }
                        for (int i = 0; i < pls.size(); i++) {
                            accountBox.addView(accountCheck(a, pls.get(i), videoId, entryIds[i]));
                        }
                    });
                } catch (Throwable e) {
                    App.MAIN.post(() -> {
                        accountBox.removeAllViews();
                        TextView t = new TextView(a);
                        t.setText("Konto-Playlists nicht erreichbar: " + e.getMessage());
                        t.setTextColor(a.getResources().getColor(R.color.text_dim));
                        accountBox.addView(t);
                    });
                }
            });
        }

        new AlertDialog.Builder(a)
                .setTitle("Speichern in …")
                .setView(sv)
                .setPositiveButton("Fertig", null)
                .setNeutralButton("+ Neue Playlist", (d, w) -> create(a, video, null))
                .show();
    }

    private static CheckBox localCheck(Activity a, String id, String name, Item video) {
        CheckBox cb = new CheckBox(a);
        cb.setText(name);
        cb.setTextColor(a.getResources().getColor(R.color.text));
        cb.setChecked(LocalPlaylists.contains(id, video.url));
        cb.setOnCheckedChangeListener((b, on) -> {
            if (on) LocalPlaylists.add(id, video);
            else LocalPlaylists.remove(id, video.url);
            Toast.makeText(a, on ? "Gespeichert in „" + name + "“" : "Entfernt aus „" + name + "“",
                    Toast.LENGTH_SHORT).show();
        });
        return cb;
    }

    private static CheckBox accountCheck(Activity a, Item pl, String videoId, String entryId) {
        CheckBox cb = new CheckBox(a);
        cb.setText(pl.title + "  ·  " + Account.privacyLabel(pl.privacy));
        cb.setTextColor(a.getResources().getColor(R.color.text));
        cb.setChecked(entryId != null);
        final String[] entry = {entryId};
        cb.setOnCheckedChangeListener((b, on) -> {
            cb.setEnabled(false);
            App.IO.execute(() -> {
                try {
                    if (on) {
                        entry[0] = Account.addToPlaylist(pl.apiPlaylistId, videoId);
                    } else {
                        String e = entry[0] != null ? entry[0] : Account.findInPlaylist(pl.apiPlaylistId, videoId);
                        if (e != null) Account.removeFromPlaylist(e);
                        entry[0] = null;
                    }
                    App.MAIN.post(() -> {
                        cb.setEnabled(true);
                        Toast.makeText(a, on ? "Gespeichert in „" + pl.title + "“"
                                : "Entfernt aus „" + pl.title + "“", Toast.LENGTH_SHORT).show();
                    });
                } catch (Throwable e) {
                    App.MAIN.post(() -> {
                        cb.setOnCheckedChangeListener(null);
                        cb.setChecked(!on);
                        cb.setEnabled(true);
                        Toast.makeText(a, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        return cb;
    }

    // ------------------------------------------------------------ Neu / Bearbeiten

    private static final String[] PRIVACY = {"private", "unlisted", "public"};
    private static final String[] PRIVACY_LABEL = {"Privat", "Nicht gelistet", "Öffentlich"};

    /**
     * Neue Playlist anlegen. Ist video != null, wird es gleich hineingelegt.
     * Ohne Google-Konto: nur auf dem Gerät.
     */
    public static void create(Activity a, Item video, Done done) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);
        EditText name = new EditText(a);
        name.setHint("Name der Playlist");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(name);

        RadioGroup rg = new RadioGroup(a);
        rg.setPadding(0, dp(a, 12), 0, 0);
        int idLocal = View.generateViewId();
        RadioButton local = radio(a, "Nur auf diesem Gerät", idLocal);
        rg.addView(local);
        int[] ids = new int[PRIVACY.length];
        if (Account.isLoggedIn()) {
            box.addView(section(a, "SPEICHERN"));
            for (int i = 0; i < PRIVACY.length; i++) {
                ids[i] = View.generateViewId();
                rg.addView(radio(a, "Google-Konto · " + PRIVACY_LABEL[i], ids[i]));
            }
            rg.check(ids[0]);
        } else {
            rg.check(idLocal);
        }
        box.addView(rg);

        new AlertDialog.Builder(a)
                .setTitle("Neue Playlist")
                .setView(box)
                .setPositiveButton("Erstellen", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) n = "Meine Playlist";
                    final String title = n;
                    int checked = rg.getCheckedRadioButtonId();
                    if (checked == idLocal || !Account.isLoggedIn()) {
                        String id = LocalPlaylists.create(title);
                        if (video != null) LocalPlaylists.add(id, video);
                        Toast.makeText(a, "Playlist „" + title + "“ erstellt", Toast.LENGTH_SHORT).show();
                        if (done != null) done.done();
                        return;
                    }
                    String privacy = PRIVACY[0];
                    for (int i = 0; i < ids.length; i++) if (ids[i] == checked) privacy = PRIVACY[i];
                    final String pr = privacy;
                    App.IO.execute(() -> {
                        try {
                            String id = Account.createPlaylist(title, pr);
                            if (video != null && id != null) Account.addToPlaylist(id, Util.videoId(video.url));
                            App.MAIN.post(() -> {
                                Toast.makeText(a, "Playlist „" + title + "“ erstellt", Toast.LENGTH_SHORT).show();
                                if (done != null) done.done();
                            });
                        } catch (Throwable e) {
                            App.MAIN.post(() -> Toast.makeText(a, "Fehler: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static RadioButton radio(Activity a, String text, int id) {
        RadioButton r = new RadioButton(a);
        r.setId(id);
        r.setText(text);
        r.setTextColor(a.getResources().getColor(R.color.text));
        return r;
    }

    /** Langes Drücken auf eine eigene Playlist: Umbenennen, Sichtbarkeit, Löschen. */
    public static void manage(Activity a, Item pl, Done done) {
        boolean local = pl.localPlaylistId != null;
        if (local && LocalPlaylists.WATCH_LATER.equals(pl.localPlaylistId)) return;
        if (!local && pl.apiPlaylistId == null) return;
        String[] options = local
                ? new String[]{"Umbenennen", "Löschen"}
                : new String[]{"Umbenennen", "Sichtbarkeit ändern", "Löschen"};
        new AlertDialog.Builder(a)
                .setTitle(pl.title)
                .setItems(options, (d, which) -> {
                    String opt = options[which];
                    if (opt.equals("Umbenennen")) rename(a, pl, done);
                    else if (opt.equals("Löschen")) delete(a, pl, done);
                    else privacy(a, pl, done);
                })
                .show();
    }

    private static void rename(Activity a, Item pl, Done done) {
        EditText name = new EditText(a);
        name.setText(pl.title);
        name.setSelection(name.getText().length());
        LinearLayout box = new LinearLayout(a);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);
        box.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle("Umbenennen")
                .setView(box)
                .setPositiveButton("Speichern", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    if (pl.localPlaylistId != null) {
                        LocalPlaylists.rename(pl.localPlaylistId, n);
                        if (done != null) done.done();
                    } else {
                        runAccount(a, () -> Account.updatePlaylist(pl.apiPlaylistId, n,
                                pl.privacy != null ? pl.privacy : "private"), done);
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void privacy(Activity a, Item pl, Done done) {
        int checked = 0;
        for (int i = 0; i < PRIVACY.length; i++) if (PRIVACY[i].equals(pl.privacy)) checked = i;
        new AlertDialog.Builder(a)
                .setTitle("Sichtbarkeit")
                .setSingleChoiceItems(PRIVACY_LABEL, checked, (d, which) -> {
                    d.dismiss();
                    runAccount(a, () -> Account.updatePlaylist(pl.apiPlaylistId, pl.title, PRIVACY[which]), done);
                })
                .show();
    }

    private static void delete(Activity a, Item pl, Done done) {
        new AlertDialog.Builder(a)
                .setTitle("„" + pl.title + "“ löschen?")
                .setMessage(pl.localPlaylistId != null ? "Die Playlist wird von diesem Gerät gelöscht."
                        : "Die Playlist wird aus deinem Google-Konto gelöscht.")
                .setPositiveButton("Löschen", (d, w) -> {
                    if (pl.localPlaylistId != null) {
                        LocalPlaylists.delete(pl.localPlaylistId);
                        if (done != null) done.done();
                    } else {
                        runAccount(a, () -> Account.deletePlaylist(pl.apiPlaylistId), done);
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private interface AccountAction {
        void run() throws Exception;
    }

    private static void runAccount(Activity a, AccountAction action, Done done) {
        App.IO.execute(() -> {
            try {
                action.run();
                App.MAIN.post(() -> {
                    if (done != null) done.done();
                });
            } catch (Throwable e) {
                App.MAIN.post(() -> Toast.makeText(a, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}
