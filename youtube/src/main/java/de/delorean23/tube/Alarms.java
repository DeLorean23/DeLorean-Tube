package de.delorean23.tube;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Wecker: mit einem Video oder einer Playlist aufwachen.
 * Quelle: "wl" Später ansehen, "dl" Downloads, "lpl:<id>" / "apl:<id>" Playlist, "video:<url>" ein Video.
 * Created by DeLorean23
 */
public final class Alarms {

    public static final class Alarm {
        public int id;
        public int hour = 7, minute = 0;
        /** Bit 0 = Montag … Bit 6 = Sonntag; 0 = einmalig */
        public int days;
        public boolean enabled = true;
        public String source = "wl";
        public String sourceLabel = "Später ansehen";
        public boolean shuffle;
        /** Lautstärke in Prozent der Medien-Lautstärke */
        public int volume = 60;
        /** Langsam lauter werden (30 s) */
        public boolean ramp = true;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("h", hour).put("m", minute).put("d", days)
                    .put("on", enabled).put("src", source).put("lbl", sourceLabel)
                    .put("sh", shuffle).put("vol", volume).put("ramp", ramp);
        }

        static Alarm fromJson(JSONObject o) {
            Alarm a = new Alarm();
            a.id = o.optInt("id");
            a.hour = o.optInt("h", 7);
            a.minute = o.optInt("m");
            a.days = o.optInt("d");
            a.enabled = o.optBoolean("on", true);
            a.source = o.optString("src", "wl");
            a.sourceLabel = o.optString("lbl", "Später ansehen");
            a.shuffle = o.optBoolean("sh");
            a.volume = o.optInt("vol", 60);
            a.ramp = o.optBoolean("ramp", true);
            return a;
        }

        public String time() {
            return String.format(Locale.GERMANY, "%02d:%02d", hour, minute);
        }

        public String daysLabel() {
            if (days == 0) return "Einmalig";
            if (days == 0x7F) return "Täglich";
            if (days == 0x1F) return "Mo–Fr";
            if (days == 0x60) return "Sa, So";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if ((days & (1 << i)) != 0) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(DAY_NAMES[i]);
                }
            }
            return sb.toString();
        }
    }

    public static final String[] DAY_NAMES = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};

    private Alarms() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("alarms", Context.MODE_PRIVATE);
    }

    public static synchronized List<Alarm> all() {
        List<Alarm> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Alarm.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized Alarm get(int id) {
        for (Alarm a : all()) if (a.id == id) return a;
        return null;
    }

    private static void saveAll(List<Alarm> l) {
        try {
            JSONArray a = new JSONArray();
            for (Alarm x : l) a.put(x.toJson());
            prefs().edit().putString("list", a.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    /** Neu oder geändert speichern und neu planen. */
    public static synchronized void save(Context c, Alarm alarm) {
        List<Alarm> l = all();
        if (alarm.id == 0) {
            int max = 0;
            for (Alarm a : l) max = Math.max(max, a.id);
            alarm.id = max + 1;
            l.add(alarm);
        } else {
            boolean found = false;
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).id == alarm.id) {
                    l.set(i, alarm);
                    found = true;
                }
            }
            if (!found) l.add(alarm);
        }
        saveAll(l);
        schedule(c, alarm);
    }

    public static synchronized void delete(Context c, int id) {
        List<Alarm> l = all();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id == id) l.remove(i);
        saveAll(l);
        cancel(c, id);
    }

    /** Nächster Zeitpunkt (ms) ab jetzt. */
    public static long nextTime(Alarm a) {
        Calendar now = Calendar.getInstance();
        Calendar t = (Calendar) now.clone();
        t.set(Calendar.HOUR_OF_DAY, a.hour);
        t.set(Calendar.MINUTE, a.minute);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 8; i++) {
            if (t.after(now)) {
                // Calendar: SUNDAY=1 … SATURDAY=7  ->  Mo=0 … So=6
                int dow = (t.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                if (a.days == 0 || (a.days & (1 << dow)) != 0) return t.getTimeInMillis();
            }
            t.add(Calendar.DAY_OF_MONTH, 1);
        }
        return t.getTimeInMillis();
    }

    /** "in 7 Std. 20 Min." */
    public static String untilLabel(long when) {
        long min = Math.max(1, (when - System.currentTimeMillis() + 59_999) / 60_000);
        long h = min / 60, m = min % 60;
        if (h >= 24) return "in " + (h / 24) + " T. " + (h % 24) + " Std.";
        if (h > 0) return "in " + h + " Std. " + m + " Min.";
        return "in " + m + " Min.";
    }

    private static PendingIntent ringIntent(Context c, int id, int flagsExtra) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_RING).putExtra("id", id);
        int flags = flagsExtra | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, 1000 + id, i, flags);
    }

    public static void schedule(Context c, Alarm a) {
        if (!a.enabled) {
            cancel(c, a.id);
            return;
        }
        setAt(c, a.id, nextTime(a));
    }

    /** Schlummern: in min Minuten nochmal klingeln. */
    public static void snooze(Context c, int id, int min) {
        setAt(c, id, System.currentTimeMillis() + min * 60_000L);
    }

    private static void setAt(Context c, int id, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = ringIntent(c, id, PendingIntent.FLAG_UPDATE_CURRENT);
        Intent show = new Intent(c, AlarmsActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent showPi = PendingIntent.getActivity(c, 999, show, flags);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } else {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi), pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    public static void cancel(Context c, int id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(ringIntent(c, id, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** Nach Neustart / Zeitänderung / Update alle wieder planen. */
    public static void scheduleAll(Context c) {
        for (Alarm a : all()) {
            if (a.enabled) schedule(c, a);
            else cancel(c, a.id);
        }
    }

    /** Nach dem Klingeln: einmalige Wecker ausschalten, wiederholende neu planen. */
    static void afterRing(Context c, int id) {
        Alarm a = get(id);
        if (a == null) return;
        if (a.days == 0) {
            a.enabled = false;
            save(c, a);
        } else {
            schedule(c, a);
        }
    }

    /** Darf die App genaue Wecker stellen? (Android 12+ ggf. in den Einstellungen erlauben) */
    public static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am == null || am.canScheduleExactAlarms();
    }
}
