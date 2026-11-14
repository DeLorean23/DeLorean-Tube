package de.delorean23.tube;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Downloads: heruntergeladene MP3s und MP4s ansehen, offline abspielen, teilen, löschen.
 * Created by DeLorean23
 */
public class DownloadsActivity extends AppCompatActivity {

    private static final int FILTER_ALL = 0, FILTER_AUDIO = 1, FILTER_VIDEO = 2;

    private ItemAdapter adapter;
    private SwipeRefreshLayout refresh;
    private TextView emptyText;
    private MiniPlayer mini;
    private int filter = FILTER_ALL;
    private final List<Downloads.Entry> shown = new ArrayList<>();
    private LinearLayout chips;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.listTitle)).setText("Downloads");
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        emptyText = findViewById(R.id.emptyText);
        refresh = findViewById(R.id.refresh);
        refresh.setColorSchemeResources(R.color.accent);
        refresh.setProgressBackgroundColorSchemeResource(R.color.card);
        refresh.setOnRefreshListener(this::load);

        RecyclerView list = findViewById(R.id.list);
        adapter = new ItemAdapter(this, this::play);
        adapter.setCompact(true);
        list.setLayoutManager(ItemAdapter.layout(this, adapter));
        list.setAdapter(adapter);
        adapter.setOnLongClick(this::menu);
        adapter.setHeader(header());
        mini = new MiniPlayer(this, findViewById(R.id.contentFrame));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mini.onResume();
        load();
    }

    @Override
    protected void onPause() {
        mini.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mini.onStop();
    }

    @Override
    protected void onDestroy() {
        mini.release();
        super.onDestroy();
    }

    private View header() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setPadding(dp(12), dp(8), dp(4), dp(10));
        hs.addView(chips);
        chips.addView(chip("Alle abspielen", R.drawable.ic_play, v -> playAll(false)));
        chips.addView(chip("Zufällig", R.drawable.ic_shuffle, v -> playAll(true)));
        chips.addView(chip("Alle", 0, v -> setFilter(FILTER_ALL)));
        chips.addView(chip("Musik", 0, v -> setFilter(FILTER_AUDIO)));
        chips.addView(chip("Videos", 0, v -> setFilter(FILTER_VIDEO)));
        updateChips();
        return hs;
    }

    private TextView chip(String text, int icon, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12), 0, dp(14), 0);
        t.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        t.setCompoundDrawablePadding(dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private void updateChips() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView t = (TextView) chips.getChildAt(i);
            boolean on = i >= 2 && (i - 2) == filter;
            t.setBackgroundResource(on ? R.drawable.chip_on_bg : R.drawable.chip_bg);
            t.setTextColor(getResources().getColor(on ? R.color.black : R.color.text));
        }
    }

    private void setFilter(int f) {
        filter = f;
        updateChips();
        load();
    }

    private void load() {
        App.IO.execute(() -> {
            List<Downloads.Entry> all = Downloads.all();
            List<Downloads.Entry> keep = new ArrayList<>();
            for (Downloads.Entry e : all) {
                if (!Downloads.exists(this, e)) {
                    Downloads.remove(e.uri); // vom Nutzer gelöscht
                    continue;
                }
                if (filter == FILTER_AUDIO && !e.isAudio()) continue;
                if (filter == FILTER_VIDEO && e.isAudio()) continue;
                keep.add(e);
            }
            App.MAIN.post(() -> {
                refresh.setRefreshing(false);
                shown.clear();
                shown.addAll(keep);
                List<Item> items = new ArrayList<>();
                for (Downloads.Entry e : keep) items.add(e.asItem());
                adapter.set(items);
                emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                emptyText.setText("Noch keine Downloads.\nBei einem Video auf „Download“ tippen.");
            });
        });
    }

    private Downloads.Entry entryFor(Item it) {
        int i = adapter.items().indexOf(it);
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    private void play(Item it) {
        int i = adapter.items().indexOf(it);
        startList(new ArrayList<>(adapter.items()), Math.max(0, i), false);
    }

    private void playAll(boolean shuffle) {
        List<Item> items = new ArrayList<>(adapter.items());
        if (items.isEmpty()) return;
        startList(items, shuffle ? new java.util.Random().nextInt(items.size()) : 0, shuffle);
    }

    private void startList(List<Item> items, int index, boolean shuffle) {
        if (items.isEmpty()) return;
        PlayQueue.playList(items, index, shuffle, "Downloads");
        Item first = PlayQueue.current();
        if (first == null) return;
        startActivity(PlayerActivity.intent(this, first.url, first).putExtra("queue", true));
    }

    private void menu(Item it) {
        Downloads.Entry e = entryFor(it);
        if (e == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Abspielen");
        actions.add(() -> play(it));
        labels.add("Mit anderer App öffnen");
        actions.add(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), e.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Throwable t) {
                Toast.makeText(this, "Keine passende App gefunden", Toast.LENGTH_SHORT).show();
            }
        });
        labels.add("Teilen");
        actions.add(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(e.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Teilen")));
        if (e.videoUrl != null) {
            labels.add("Original-Video auf YouTube öffnen");
            actions.add(() -> {
                Item v = new Item();
                v.url = e.videoUrl;
                v.title = e.title;
                PlayQueue.playSingle(v);
                startActivity(PlayerActivity.intent(this, e.videoUrl, v));
            });
        }
        labels.add("Löschen");
        actions.add(() -> new AlertDialog.Builder(this)
                .setMessage("„" + e.title + "“ vom Gerät löschen?")
                .setPositiveButton("Löschen", (d, w) -> delete(e))
                .setNegativeButton("Abbrechen", null)
                .show());
        new AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private void delete(Downloads.Entry e) {
        Uri u = Uri.parse(e.uri);
        try {
            if ("file".equals(u.getScheme())) {
                //noinspection ResultOfMethodCallIgnored
                new java.io.File(u.getPath()).delete();
            } else {
                getContentResolver().delete(u, null, null);
            }
            Downloads.remove(e.uri);
            Toast.makeText(this, "Gelöscht", Toast.LENGTH_SHORT).show();
            load();
        } catch (SecurityException se) {
            // Datei gehört nicht mehr der App (z. B. nach Neuinstallation): Android fragt nach
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(),
                            Collections.singletonList(u));
                    Downloads.remove(e.uri);
                    startIntentSenderForResult(pi.getIntentSender(), 9, null, 0, 0, 0);
                } catch (Throwable t) {
                    Toast.makeText(this, "Löschen nicht möglich: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Löschen nicht möglich – bitte im Dateimanager löschen", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Löschen nicht möglich: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
