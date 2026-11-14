package de.delorean23.tube;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

/** Einstellungen. Created by DeLorean23 */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout box;

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
        t.setText("Einstellungen");
        t.setTextSize(18);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(24));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        section("Wiedergabe");
        toggle("Autoplay", "Nach dem Video automatisch ein passendes nächstes abspielen", "autoplay", true);
        toggle("Weiterschauen", "Videos dort fortsetzen, wo du aufgehört hast", "resume", true);
        toggle("Bild-im-Bild", "Beim Verlassen der App läuft das Video in einem kleinen Fenster weiter",
                "pip", true);

        section("Extras");
        toggle("SponsorBlock", "Werbeeinblendungen der YouTuber, Abo-Aufrufe usw. automatisch überspringen",
                "sb_on", true);
        button("SponsorBlock: was überspringen? …", this::askSponsorCategories);
        toggle("Dislikes anzeigen", "Geschätzte „Mag ich nicht“-Zahl (Return YouTube Dislike)", "ryd_on", true);
        button("Klang: Equalizer, Bass-Boost, Lautstärke angleichen …",
                () -> startActivity(new android.content.Intent(this, EqualizerActivity.class)));

        button("Wecker: mit einem Video oder einer Playlist aufwachen …",
                () -> startActivity(new android.content.Intent(this, AlarmsActivity.class)));

        section("Startseite und Abos");
        toggle("Shorts im Abo-Feed ausblenden", "Im Tab „Abos“ nur normale Videos zeigen",
                "hide_shorts_feed", false);

        section("Suche und Verlauf");
        toggle("Suchverlauf speichern", "Zuletzt gesuchte Begriffe als Vorschlag zeigen", "save_searches", true);
        button("Suchverlauf löschen", () -> {
            SearchHistory.clear();
            Toast.makeText(this, "Suchverlauf gelöscht", Toast.LENGTH_SHORT).show();
        });
        button("Wiedergabeverlauf löschen", () -> confirm("Verlauf und „Weiterschauen“-Positionen löschen?", () -> {
            History.clear();
            Positions.clear();
            Toast.makeText(this, "Verlauf gelöscht", Toast.LENGTH_SHORT).show();
        }));

        section("Über");
        TextView about = new TextView(this);
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.setText("DeLorean Tube " + ver + "\nCreated by DeLorean23");
        about.setTextColor(getResources().getColor(R.color.text_dim));
        about.setPadding(dp(16), dp(4), dp(16), dp(8));
        box.addView(about);
    }

    private void askSponsorCategories() {
        boolean[] on = new boolean[SponsorBlock.CATEGORIES.length];
        for (int i = 0; i < on.length; i++) on[i] = SponsorBlock.categoryOn(i);
        new AlertDialog.Builder(this)
                .setTitle("Überspringen")
                .setMultiChoiceItems(SponsorBlock.LABELS, on, (d, i, c) -> SponsorBlock.setCategory(i, c))
                .setPositiveButton("Fertig", null)
                .show();
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTextColor(getResources().getColor(R.color.accent));
        t.setPadding(dp(16), dp(20), dp(16), dp(6));
        box.addView(t);
    }

    private void toggle(String title, String summary, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(getResources().getColor(R.color.text));
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(13);
        s.setTextColor(getResources().getColor(R.color.text_dim));
        texts.addView(t);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(Playback.prefs().getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> Playback.setBool(key, on));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        box.addView(row);
    }

    private void button(String title, Runnable r) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        t.setOnClickListener(v -> r.run());
        box.addView(t);
    }

    private void confirm(String msg, Runnable r) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Löschen", (d, w) -> r.run())
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
