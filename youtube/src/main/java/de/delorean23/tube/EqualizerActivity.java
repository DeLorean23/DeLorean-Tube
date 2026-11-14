package de.delorean23.tube;

import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Equalizer, Bass-Boost und Lautstärke angleichen. Created by DeLorean23 */
public class EqualizerActivity extends AppCompatActivity {

    private LinearLayout box;
    private Equalizer probe; // nur zum Auslesen der Bänder, wenn gerade nichts läuft
    private final List<SeekBar> bands = new ArrayList<>();
    private TextView presetView;

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
        t.setText("Klang");
        t.setTextSize(18);
        t.setTextColor(getResources().getColor(R.color.text));
        bar.addView(t);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), dp(24));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        build();
    }

    @Override
    protected void onDestroy() {
        if (probe != null) {
            try {
                probe.release();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    private Equalizer eq() {
        Equalizer e = AudioFx.equalizer();
        if (e != null) return e;
        if (probe == null) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                probe = new Equalizer(0, am.generateAudioSessionId());
            } catch (Throwable ignored) {
            }
        }
        return probe;
    }

    private void build() {
        Equalizer e = eq();
        // Equalizer
        SwitchCompat on = sw("Equalizer", AudioFx.eqOn());
        on.setOnCheckedChangeListener((b, c) -> {
            Playback.setBool("fx_eq", c);
            AudioFx.apply();
        });
        if (e == null) {
            box.addView(note("Dieses Gerät stellt keinen Equalizer bereit."));
        } else {
            presetView = new TextView(this);
            presetView.setTextSize(15);
            presetView.setTextColor(getResources().getColor(R.color.text));
            presetView.setBackgroundResource(R.drawable.chip_bg);
            presetView.setPadding(dp(14), dp(10), dp(14), dp(10));
            presetView.setOnClickListener(v -> askPreset());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            box.addView(presetView, lp);
            updatePresetLabel();
            short[] range = e.getBandLevelRange();
            for (short b = 0; b < e.getNumberOfBands(); b++) {
                final short band = b;
                int hz = e.getCenterFreq(b) / 1000;
                TextView l = note(hz >= 1000 ? String.format(Locale.GERMANY, "%.1f kHz", hz / 1000f) : hz + " Hz");
                box.addView(l);
                SeekBar sb = new SeekBar(this);
                sb.setMax(range[1] - range[0]);
                sb.setProgress(AudioFx.bandLevel(b) - range[0]);
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean user) {
                        if (!user) return;
                        Playback.prefs().edit().putInt("fx_band" + band, p + range[0])
                                .putInt("fx_preset", -1).apply();
                        if (!AudioFx.eqOn()) on.setChecked(true);
                        updatePresetLabel();
                        AudioFx.apply();
                    }

                    @Override public void onStartTrackingTouch(SeekBar s) { }
                    @Override public void onStopTrackingTouch(SeekBar s) { }
                });
                bands.add(sb);
                box.addView(sb);
            }
        }

        // Bass-Boost
        box.addView(header("Bass-Boost"));
        SeekBar bass = new SeekBar(this);
        bass.setMax(1000);
        bass.setProgress(AudioFx.bassStrength());
        bass.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                Playback.prefs().edit().putInt("fx_bass", p).apply();
                AudioFx.apply();
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(bass);

        // Lautstärke angleichen
        SwitchCompat norm = sw("Lautstärke angleichen", AudioFx.normalizeOn());
        if (!AudioFx.normalizeSupported()) {
            norm.setEnabled(false);
            box.addView(note("Gibt es erst ab Android 9."));
        } else {
            box.addView(note("Leise Videos werden lauter, laute leiser – alle klingen etwa gleich laut."));
        }
        norm.setOnCheckedChangeListener((b, c) -> {
            Playback.setBool("fx_norm", c);
            AudioFx.apply();
        });
    }

    private void askPreset() {
        Equalizer e = eq();
        if (e == null) return;
        int n = e.getNumberOfPresets();
        String[] names = new String[n + 1];
        names[0] = "Eigene Einstellung";
        for (short i = 0; i < n; i++) names[i + 1] = presetName(e.getPresetName(i));
        new AlertDialog.Builder(this)
                .setTitle("Voreinstellung")
                .setSingleChoiceItems(names, AudioFx.preset() + 1, (d, w) -> {
                    Playback.prefs().edit().putInt("fx_preset", w - 1).putBoolean("fx_eq", true).apply();
                    if (w > 0) {
                        // Regler auf die Voreinstellung stellen
                        try {
                            e.usePreset((short) (w - 1));
                            short[] range = e.getBandLevelRange();
                            for (short b = 0; b < e.getNumberOfBands() && b < bands.size(); b++) {
                                short lvl = e.getBandLevel(b);
                                Playback.prefs().edit().putInt("fx_band" + b, lvl).apply();
                                bands.get(b).setProgress(lvl - range[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    AudioFx.apply();
                    updatePresetLabel();
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    private static String presetName(String n) {
        switch (n) {
            case "Normal": return "Normal";
            case "Classical": return "Klassik";
            case "Dance": return "Dance";
            case "Flat": return "Neutral";
            case "Folk": return "Folk";
            case "Heavy Metal": return "Heavy Metal";
            case "Hip Hop": return "Hip-Hop";
            case "Jazz": return "Jazz";
            case "Pop": return "Pop";
            case "Rock": return "Rock";
            default: return n;
        }
    }

    private void updatePresetLabel() {
        if (presetView == null) return;
        Equalizer e = eq();
        int p = AudioFx.preset();
        String name = "Eigene Einstellung";
        try {
            if (e != null && p >= 0 && p < e.getNumberOfPresets()) name = presetName(e.getPresetName((short) p));
        } catch (Throwable ignored) {
        }
        presetView.setText("Voreinstellung: " + name + "  ▾");
    }

    private SwitchCompat sw(String title, boolean checked) {
        SwitchCompat s = new SwitchCompat(this);
        s.setText(title);
        s.setTextSize(16);
        s.setTextColor(getResources().getColor(R.color.text));
        s.setChecked(checked);
        s.setPadding(0, dp(14), 0, dp(6));
        box.addView(s);
        return s;
    }

    private TextView header(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(16);
        v.setTextColor(getResources().getColor(R.color.text));
        v.setPadding(0, dp(18), 0, dp(4));
        return v;
    }

    private TextView note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(getResources().getColor(R.color.text_dim));
        v.setPadding(0, dp(8), 0, 0);
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
