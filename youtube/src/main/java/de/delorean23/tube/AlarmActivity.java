package de.delorean23.tube;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bildschirm, wenn der Wecker klingelt (auch über dem Sperrbildschirm). Created by DeLorean23 */
public class AlarmActivity extends AppCompatActivity {

    private static final List<AlarmActivity> open = new ArrayList<>();

    private TextView clock, label, title;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date()));
            App.MAIN.postDelayed(this, 10_000);
        }
    };

    static void closeAll() {
        for (AlarmActivity a : new ArrayList<>(open)) a.finish();
    }

    static void refreshAll() {
        for (AlarmActivity a : new ArrayList<>(open)) a.refresh();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(getResources().getColor(R.color.bg));
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView icon = new TextView(this);
        icon.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_alarm, 0, 0);
        root.addView(icon);

        clock = text(64, R.color.text);
        root.addView(clock);
        label = text(18, R.color.text_dim);
        root.addView(label);
        title = text(18, R.color.text);
        title.setPadding(0, dp(16), 0, dp(32));
        title.setMaxLines(3);
        root.addView(title);

        root.addView(button("Stopp", true, v -> send(AlarmService.ACTION_STOP)));
        root.addView(button("Schlummern " + AlarmService.snoozeMinutes() + " Min.", false,
                v -> send(AlarmService.ACTION_SNOOZE)));
        root.addView(button("Weiterhören", false, v -> {
            send(AlarmService.ACTION_KEEP);
            if (PlaybackService.nowPlaying() != null) {
                startActivity(new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        }));
        setContentView(root);
        open.add(this);
        refresh();
        if (AlarmService.ringing == null) finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.MAIN.post(tick);
    }

    @Override
    protected void onPause() {
        App.MAIN.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        open.remove(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Zurück-Taste beendet den Wecker nicht aus Versehen
    }

    private void refresh() {
        Alarms.Alarm al = AlarmService.ringing;
        clock.setText(new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date()));
        label.setText(al != null ? "Wecker · " + al.sourceLabel : "Wecker");
        title.setText(AlarmService.nowTitle != null ? AlarmService.nowTitle : "");
    }

    private void send(String action) {
        try {
            startService(new Intent(this, AlarmService.class).setAction(action));
        } catch (Throwable ignored) {
        }
        finish();
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(getResources().getColor(color));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setBackgroundResource(primary ? R.drawable.chip_on_bg : R.drawable.chip_bg);
        b.setTextColor(getResources().getColor(primary ? R.color.black : R.color.text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), dp(56));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
