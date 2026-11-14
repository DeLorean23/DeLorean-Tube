package de.delorean23.tube;

import android.Manifest;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;

/** Wecker-Liste: mit einem Video oder einer Playlist aufwachen. Created by DeLorean23 */
public class AlarmsActivity extends AppCompatActivity {

    private LinearLayout box;

    /** Wecker mit einem bestimmten Video anlegen. */
    public static Intent forVideo(android.content.Context c, String url, String title) {
        return new Intent(c, AlarmsActivity.class).putExtra("video_url", url).putExtra("video_title", title);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.bg));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(12), 0);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(android.R.color.transparent);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = new TextView(this);
        t.setText("Wecker");
        t.setTextSize(18);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageButton add = new ImageButton(this);
        add.setImageResource(R.drawable.ic_add);
        add.setBackgroundResource(android.R.color.transparent);
        add.setContentDescription("Neuer Wecker");
        add.setOnClickListener(v -> edit(new Alarms.Alarm(), true));
        bar.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(24));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }

        String vUrl = getIntent().getStringExtra("video_url");
        if (vUrl != null && savedInstanceState == null) {
            Alarms.Alarm a = new Alarms.Alarm();
            a.source = "video:" + vUrl;
            String vt = getIntent().getStringExtra("video_title");
            a.sourceLabel = vt != null ? vt : "Video";
            edit(a, true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        box.removeAllViews();
        // Hinweise, wenn Android etwas nicht erlaubt
        if (!Alarms.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            hint("Wecker brauchen die Erlaubnis „Wecker und Erinnerungen“. Hier tippen zum Erlauben.", () -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                }
            });
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                hint("Damit der Wecker auf dem Sperrbildschirm erscheint: „Vollbild-Benachrichtigungen“ erlauben.",
                        () -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Throwable ignored) {
                            }
                        });
            }
        }
        List<Alarms.Alarm> all = Alarms.all();
        if (all.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("Noch kein Wecker.\nOben auf + tippen, um mit einem Video oder einer Playlist aufzuwachen.");
            e.setTextColor(getResources().getColor(R.color.text_dim));
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(24), dp(48), dp(24), dp(24));
            box.addView(e);
        }
        for (Alarms.Alarm a : all) box.addView(row(a));
    }

    private void hint(String text, Runnable r) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setBackgroundColor(0x33FF3B30);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setFocusable(true);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(8), dp(12), 0);
        box.addView(t, lp);
    }

    private View row(Alarms.Alarm a) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setFocusable(true);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView time = new TextView(this);
        time.setText(a.time());
        time.setTextSize(36);
        time.setTextColor(getResources().getColor(a.enabled ? R.color.text : R.color.text_dim));
        TextView sub = new TextView(this);
        String when = a.enabled ? " · " + Alarms.untilLabel(Alarms.nextTime(a)) : "";
        sub.setText(a.daysLabel() + " · " + a.sourceLabel + when);
        sub.setTextSize(13);
        sub.setMaxLines(2);
        sub.setTextColor(getResources().getColor(R.color.text_dim));
        texts.addView(time);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(a.enabled);
        sw.setOnCheckedChangeListener((b, on) -> {
            a.enabled = on;
            Alarms.save(this, a);
            if (on) Toast.makeText(this, "Wecker " + Alarms.untilLabel(Alarms.nextTime(a)), Toast.LENGTH_SHORT).show();
            render();
        });
        row.addView(sw);
        row.setOnClickListener(v -> edit(a, false));
        row.setOnLongClickListener(v -> {
            confirmDelete(a);
            return true;
        });
        return row;
    }

    private void confirmDelete(Alarms.Alarm a) {
        new AlertDialog.Builder(this)
                .setMessage("Wecker " + a.time() + " löschen?")
                .setPositiveButton("Löschen", (d, w) -> {
                    Alarms.delete(this, a.id);
                    render();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ------------------------------------------------------------ Bearbeiten

    private void edit(Alarms.Alarm original, boolean isNew) {
        // auf einer Kopie arbeiten: Abbrechen ändert nichts
        Alarms.Alarm a;
        try {
            a = Alarms.Alarm.fromJson(original.toJson());
        } catch (Exception e) {
            a = original;
        }
        editCopy(a, isNew);
    }

    private void editCopy(Alarms.Alarm a, boolean isNew) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);

        TextView time = new TextView(this);
        time.setTextSize(44);
        time.setGravity(Gravity.CENTER);
        time.setTextColor(getResources().getColor(R.color.text));
        time.setText(a.time());
        time.setFocusable(true);
        time.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            a.hour = h;
            a.minute = m;
            time.setText(a.time());
        }, a.hour, a.minute, true).show());
        f.addView(time);
        f.addView(small("Zum Ändern auf die Uhrzeit tippen"));

        f.addView(small("Tage (keiner = einmalig)"));
        LinearLayout days = new LinearLayout(this);
        days.setGravity(Gravity.CENTER);
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            TextView d = new TextView(this);
            d.setText(Alarms.DAY_NAMES[i]);
            d.setGravity(Gravity.CENTER);
            d.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            d.setLayoutParams(lp);
            Runnable paint = () -> {
                boolean on = (a.days & bit) != 0;
                d.setBackgroundResource(on ? R.drawable.chip_on_bg : R.drawable.chip_bg);
                d.setTextColor(getResources().getColor(on ? R.color.black : R.color.text));
            };
            paint.run();
            d.setOnClickListener(v -> {
                a.days ^= bit;
                paint.run();
            });
            days.addView(d);
        }
        f.addView(days);

        f.addView(small("Aufwachen mit"));
        TextView src = new TextView(this);
        src.setTextSize(16);
        src.setTextColor(getResources().getColor(R.color.text));
        src.setBackgroundResource(R.drawable.chip_bg);
        src.setPadding(dp(14), dp(10), dp(14), dp(10));
        src.setText(a.sourceLabel + "  ▾");
        src.setFocusable(true);
        src.setOnClickListener(v -> pickSource(a, () -> src.setText(a.sourceLabel + "  ▾")));
        f.addView(src);

        SwitchCompat shuffle = new SwitchCompat(this);
        shuffle.setText("Zufällige Reihenfolge");
        shuffle.setTextColor(getResources().getColor(R.color.text));
        shuffle.setChecked(a.shuffle);
        shuffle.setPadding(0, dp(12), 0, 0);
        f.addView(shuffle);

        SwitchCompat ramp = new SwitchCompat(this);
        ramp.setText("Langsam lauter werden");
        ramp.setTextColor(getResources().getColor(R.color.text));
        ramp.setChecked(a.ramp);
        ramp.setPadding(0, dp(8), 0, 0);
        f.addView(ramp);

        TextView volLabel = small("Lautstärke: " + a.volume + " %");
        f.addView(volLabel);
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(a.volume);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                volLabel.setText("Lautstärke: " + Math.max(5, p) + " %");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        f.addView(vol);

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Neuer Wecker" : "Wecker bearbeiten")
                .setView(sv)
                .setPositiveButton("Speichern", (d, w) -> {
                    a.shuffle = shuffle.isChecked();
                    a.ramp = ramp.isChecked();
                    a.volume = Math.max(5, vol.getProgress());
                    a.enabled = true;
                    Alarms.save(this, a);
                    Toast.makeText(this, "Wecker " + Alarms.untilLabel(Alarms.nextTime(a)), Toast.LENGTH_LONG).show();
                    render();
                })
                .setNegativeButton("Abbrechen", null);
        if (!isNew) b.setNeutralButton("Löschen", (d, w) -> confirmDelete(a));
        b.show();
    }

    private void pickSource(Alarms.Alarm a, Runnable done) {
        List<String> labels = new ArrayList<>();
        List<String[]> values = new ArrayList<>();
        if (a.source.startsWith("video:")) {
            labels.add("Video: " + a.sourceLabel);
            values.add(new String[]{a.source, a.sourceLabel});
        }
        labels.add("Später ansehen");
        values.add(new String[]{"wl", "Später ansehen"});
        labels.add("Downloads (geht auch ohne Internet)");
        values.add(new String[]{"dl", "Downloads"});
        for (LocalPlaylists.Playlist p : LocalPlaylists.all()) {
            if (LocalPlaylists.WATCH_LATER.equals(p.id)) continue;
            labels.add("Playlist: " + p.name);
            values.add(new String[]{"lpl:" + p.id, p.name});
        }
        Runnable show = () -> new AlertDialog.Builder(this)
                .setTitle("Aufwachen mit")
                .setItems(labels.toArray(new String[0]), (d, w) -> {
                    a.source = values.get(w)[0];
                    a.sourceLabel = values.get(w)[1];
                    done.run();
                })
                .show();
        if (!Account.isLoggedIn()) {
            show.run();
            return;
        }
        App.IO.execute(() -> {
            List<Item> pls = new ArrayList<>();
            try {
                pls.addAll(Account.myPlaylists());
            } catch (Throwable ignored) {
            }
            App.MAIN.post(() -> {
                if (isFinishing()) return;
                for (Item p : pls) {
                    if (p.apiPlaylistId == null) continue;
                    labels.add("Konto-Playlist: " + p.title);
                    values.add(new String[]{"apl:" + p.apiPlaylistId, p.title});
                }
                show.run();
            });
        });
    }

    private TextView small(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(getResources().getColor(R.color.text_dim));
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
