package de.delorean23.cloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int FILTER_ALL = 0, FILTER_TOUCH = 1;

    private Catalog catalog;
    private final List<Catalog.Game> all = new ArrayList<>();
    private final List<Catalog.Game> shown = new ArrayList<>();
    private Adapter adapter;
    private RecyclerView grid;
    private GridLayoutManager layout;
    private SwipeRefreshLayout refresh;
    private TextView status;
    private EditText search;
    private TextView chipAll, chipTouch;
    private int filter = FILTER_ALL;
    private boolean loading;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("cloud", MODE_PRIVATE);
        catalog = new Catalog(this);

        status = findViewById(R.id.status);
        search = findViewById(R.id.search);
        grid = findViewById(R.id.grid);
        refresh = findViewById(R.id.refresh);
        refresh.setColorSchemeColors(0xFFFF2BD6, 0xFF22D3EE);
        refresh.setProgressBackgroundColorSchemeColor(0xFF1A1028);
        refresh.setOnRefreshListener(() -> load(true));

        layout = new GridLayoutManager(this, columns());
        grid.setLayoutManager(layout);
        adapter = new Adapter();
        grid.setAdapter(adapter);

        LinearLayout chips = findViewById(R.id.chips);
        chipAll = chip(chips, "Alle Spiele", FILTER_ALL);
        chipTouch = chip(chips, "Mit Touch-Steuerung", FILTER_TOUCH);
        filter = prefs.getInt("filter", FILTER_ALL);
        updateChips();

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { apply(); }
        });
        search.setOnEditorActionListener((v, id, ev) -> { hideKeyboard(); grid.requestFocus(); return true; });

        findViewById(R.id.menu).setOnClickListener(this::showMenu);

        if (catalog.hasCache()) {
            all.addAll(catalog.loadCache());
            apply();
        }
        if (catalog.cacheIsOld()) load(false);

        if (!prefs.getBoolean("signin_hint", false)) {
            prefs.edit().putBoolean("signin_hint", true).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Willkommen bei DeLorean Cloud")
                    .setMessage("Zum Spielen brauchst du Xbox Game Pass Ultimate.\n\n"
                            + "Melde dich einmal mit deinem Microsoft-Konto an. Danach startet jedes Spiel "
                            + "direkt im Stream.\n\nSpiele mit dem Abzeichen \"Touch\" kannst du ohne Controller "
                            + "mit den Xbox-Tasten auf dem Bildschirm spielen.")
                    .setPositiveButton("Jetzt anmelden", (d, w) -> openSignIn())
                    .setNegativeButton("Später", null)
                    .show();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        layout.setSpanCount(columns());
    }

    private int columns() {
        float dp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        return Math.max(3, (int) (dp / 120));
    }

    private TextView chip(LinearLayout parent, String text, int which) {
        TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_chip, parent, false);
        t.setText(text);
        t.setOnClickListener(v -> {
            filter = which;
            prefs.edit().putInt("filter", filter).apply();
            updateChips();
            apply();
        });
        parent.addView(t);
        return t;
    }

    private void updateChips() {
        chipAll.setSelected(filter == FILTER_ALL);
        chipTouch.setSelected(filter == FILTER_TOUCH);
    }

    // ------------------------------------------------------------ list
    private void load(boolean byUser) {
        if (loading) return;
        loading = true;
        refresh.setRefreshing(true);
        new Thread(() -> {
            try {
                List<Catalog.Game> games = catalog.download(text -> ui.post(() -> status.setText(text)));
                ui.post(() -> {
                    all.clear();
                    all.addAll(games);
                    apply();
                    done();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    done();
                    apply();
                    if (all.isEmpty()) status.setText("Die Spieleliste konnte nicht geladen werden. "
                            + "Prüfe die Internetverbindung und ziehe die Liste nach unten, um es erneut zu versuchen.");
                    else if (byUser) Toast.makeText(this, "Aktualisieren fehlgeschlagen", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void done() {
        loading = false;
        refresh.setRefreshing(false);
    }

    private void apply() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        int touch = 0;
        for (Catalog.Game g : all) {
            if (g.touch) touch++;
            if (filter == FILTER_TOUCH && !g.touch) continue;
            if (!q.isEmpty() && !g.name.toLowerCase(Locale.ROOT).contains(q)
                    && !g.publisher.toLowerCase(Locale.ROOT).contains(q)
                    && !g.genre.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(g);
        }
        adapter.notifyDataSetChanged();
        if (!loading || !all.isEmpty()) {
            if (all.isEmpty()) status.setText("");
            else status.setText(shown.size() + " Spiele  ·  " + all.size() + " in der Cloud, "
                    + touch + " mit Touch-Steuerung");
        }
    }

    // ------------------------------------------------------------ actions
    private void play(Catalog.Game g) {
        Intent i = new Intent(this, PlayActivity.class);
        i.putExtra(PlayActivity.EXTRA_URL, Catalog.launchUrl(g));
        i.putExtra(PlayActivity.EXTRA_NAME, g.name);
        startActivity(i);
    }

    private void info(Catalog.Game g) {
        StringBuilder s = new StringBuilder();
        if (!g.publisher.isEmpty()) s.append(g.publisher).append('\n');
        if (!g.genre.isEmpty()) s.append(g.genre).append('\n');
        s.append(g.touch ? "Touch-Steuerung: ja\n" : "Touch-Steuerung: nein (Controller nötig)\n");
        if (!g.desc.isEmpty()) s.append('\n').append(g.desc);
        new AlertDialog.Builder(this)
                .setTitle(g.name)
                .setMessage(s.toString())
                .setPositiveButton("Spielen", (d, w) -> play(g))
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void openSignIn() {
        Intent i = new Intent(this, PlayActivity.class);
        i.putExtra(PlayActivity.EXTRA_URL, "https://www.xbox.com/de-DE/play");
        i.putExtra(PlayActivity.EXTRA_NAME, "Anmelden");
        i.putExtra(PlayActivity.EXTRA_SIGNIN, true);
        startActivity(i);
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Anmelden / Konto");
        m.getMenu().add(0, 2, 1, "Liste aktualisieren");
        m.getMenu().add(0, 3, 2, "Abmelden");
        m.getMenu().add(0, 4, 3, "Info");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openSignIn(); break;
                case 2: load(true); break;
                case 3: signOut(); break;
                case 4: about(); break;
            }
            return true;
        });
        m.show();
    }

    private void signOut() {
        new AlertDialog.Builder(this)
                .setTitle("Abmelden")
                .setMessage("Microsoft-Konto in DeLorean Cloud abmelden?")
                .setPositiveButton("Abmelden", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    Toast.makeText(this, "Abgemeldet", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void about() {
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        new AlertDialog.Builder(this)
                .setTitle("DeLorean Cloud " + v)
                .setMessage("Xbox Cloud Gaming mit Cover-Liste und Direktstart.\n\n"
                        + "Spieleliste: öffentlicher Xbox-Game-Pass-Katalog (Deutschland).\n"
                        + "Stream: die Xbox-Cloud-Gaming-Webseite im Handy-Modus, mit Touch-Steuerung "
                        + "für unterstützte Spiele. Ein Bluetooth-Controller geht auch.\n\n"
                        + "Lange auf ein Spiel tippen zeigt Infos.\n\n"
                        + "Xbox und Game Pass sind Marken von Microsoft. Diese App hat nichts mit Microsoft zu tun.\n\n"
                        + "Created by DeLorean23")
                .setPositiveButton("OK", null)
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // controller: A = open, Y = info, B = back
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A && f != null && !(f instanceof EditText)) {
                f.performClick();
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Y && f != null && f.getTag() instanceof Catalog.Game) {
                info((Catalog.Game) f.getTag());
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
                onBackPressed();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ adapter
    private class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_game, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Catalog.Game g = shown.get(pos);
            h.itemView.setTag(g);
            h.name.setText(g.name);
            h.touch.setVisibility(g.touch ? View.VISIBLE : View.GONE);
            String url = g.poster;
            if (!url.isEmpty()) url += (url.contains("?") ? "&" : "?") + "w=300";
            Glide.with(h.cover).load(url.isEmpty() ? null : url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.color.field)
                    .into(h.cover);
            h.itemView.setOnClickListener(v -> play(g));
            h.itemView.setOnLongClickListener(v -> { info(g); return true; });
        }

        @Override
        public int getItemCount() { return shown.size(); }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name, touch;

        Holder(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            name = v.findViewById(R.id.name);
            touch = v.findViewById(R.id.touch);
        }
    }
}
