package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Zuletzt gesuchte Begriffe. Created by DeLorean23 */
public final class SearchHistory {

    private static final int MAX = 30;

    private SearchHistory() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("search_history", Context.MODE_PRIVATE);
    }

    public static synchronized List<String> all() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void add(String q) {
        if (q == null || q.trim().isEmpty() || !Playback.saveSearches()) return;
        q = q.trim();
        List<String> l = all();
        String low = q.toLowerCase(Locale.ROOT);
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).toLowerCase(Locale.ROOT).equals(low)) l.remove(i);
        l.add(0, q);
        while (l.size() > MAX) l.remove(l.size() - 1);
        save(l);
    }

    public static synchronized void remove(String q) {
        List<String> l = all();
        l.remove(q);
        save(l);
    }

    public static synchronized void clear() {
        prefs().edit().clear().apply();
    }

    private static void save(List<String> l) {
        JSONArray a = new JSONArray();
        for (String s : l) a.put(s);
        prefs().edit().putString("list", a.toString()).apply();
    }
}
