package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merkt sich, wie weit jedes Video geschaut wurde (für "Weiterschauen" und den roten Balken).
 * Created by DeLorean23
 */
public final class Positions {

    private static final int MAX = 1000;
    /** Ab hier gilt ein Video als fertig geschaut. */
    private static final float DONE = 0.95f;

    /** videoId -> {Position ms, Dauer ms} */
    private static LinkedHashMap<String, long[]> map;
    private static long lastSave;

    private Positions() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("positions", Context.MODE_PRIVATE);
    }

    private static synchronized LinkedHashMap<String, long[]> map() {
        if (map != null) return map;
        map = new LinkedHashMap<>(64, 0.75f, true);
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                map.put(o.getString("i"), new long[]{o.getLong("p"), o.getLong("d")});
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /** Position speichern (wird oft aufgerufen, schreibt höchstens alle 10 s auf den Speicher). */
    public static synchronized void save(String url, long posMs, long durMs, boolean force) {
        String id = Util.videoId(url);
        if (id == null || durMs <= 0) return;
        LinkedHashMap<String, long[]> m = map();
        m.put(id, new long[]{posMs, durMs});
        while (m.size() > MAX) {
            Iterator<String> it = m.keySet().iterator();
            it.next();
            it.remove();
        }
        long now = System.currentTimeMillis();
        if (force || now - lastSave > 10_000) {
            lastSave = now;
            write();
        }
    }

    private static void write() {
        try {
            JSONArray a = new JSONArray();
            for (Map.Entry<String, long[]> e : map().entrySet()) {
                a.put(new JSONObject().put("i", e.getKey()).put("p", e.getValue()[0]).put("d", e.getValue()[1]));
            }
            prefs().edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Wo soll das Video starten? 0 = von vorne. */
    public static synchronized long resumePosition(String url) {
        if (!Playback.resume()) return 0;
        String id = Util.videoId(url);
        if (id == null) return 0;
        long[] v = map().get(id);
        if (v == null) return 0;
        if (v[0] < 10_000 || v[0] > v[1] * DONE) return 0;
        return Math.max(0, v[0] - 3_000); // 3 s zurück, damit man wieder reinkommt
    }

    /** Wie viel geschaut wurde (0..1), -1 = nie. Ein fast fertiges Video zählt als ganz. */
    public static synchronized float fraction(String url) {
        String id = Util.videoId(url);
        if (id == null) return -1;
        long[] v = map == null ? map().get(id) : map.get(id);
        if (v == null || v[1] <= 0) return -1;
        float f = (float) v[0] / v[1];
        return f > DONE ? 1f : f;
    }

    public static synchronized void clear() {
        map().clear();
        prefs().edit().clear().apply();
    }
}
