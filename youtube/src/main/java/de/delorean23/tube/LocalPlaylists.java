package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Playlists, die nur auf dem Gerät gespeichert sind (gehen auch ohne Google-Konto).
 * "Später ansehen" ist eine feste Playlist mit der ID "wl".
 * Created by DeLorean23
 */
public final class LocalPlaylists {

    public static final String WATCH_LATER = "wl";

    public static final class Playlist {
        public final String id;
        public String name;
        public final List<Item> items = new ArrayList<>();

        Playlist(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static List<Playlist> cache;

    private LocalPlaylists() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("local_playlists", Context.MODE_PRIVATE);
    }

    private static synchronized List<Playlist> load() {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("lists", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Playlist p = new Playlist(o.getString("id"), o.optString("name"));
                JSONArray items = o.optJSONArray("items");
                if (items != null) {
                    for (int j = 0; j < items.length(); j++) p.items.add(Item.fromJson(items.getJSONObject(j)));
                }
                cache.add(p);
            }
        } catch (Exception ignored) {
        }
        if (find(WATCH_LATER) == null) cache.add(0, new Playlist(WATCH_LATER, "Später ansehen"));
        return cache;
    }

    private static synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Playlist p : load()) {
                JSONArray items = new JSONArray();
                for (Item it : p.items) items.put(it.toJson());
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("items", items));
            }
            prefs().edit().putString("lists", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static Playlist find(String id) {
        if (cache == null) return null;
        for (Playlist p : cache) if (p.id.equals(id)) return p;
        return null;
    }

    public static synchronized Playlist get(String id) {
        load();
        return find(id);
    }

    /** Alle eigenen Playlists auf dem Gerät (ohne "Später ansehen"). */
    public static synchronized List<Playlist> all() {
        List<Playlist> out = new ArrayList<>();
        for (Playlist p : load()) if (!p.id.equals(WATCH_LATER)) out.add(p);
        return out;
    }

    public static synchronized String create(String name) {
        Playlist p = new Playlist(UUID.randomUUID().toString(), name);
        load().add(p);
        save();
        return p.id;
    }

    public static synchronized void rename(String id, String name) {
        Playlist p = get(id);
        if (p == null) return;
        p.name = name;
        save();
    }

    public static synchronized void delete(String id) {
        if (WATCH_LATER.equals(id)) return;
        Playlist p = get(id);
        if (p == null) return;
        load().remove(p);
        save();
    }

    public static synchronized boolean contains(String id, String url) {
        Playlist p = get(id);
        if (p == null) return false;
        for (Item it : p.items) if (PlayQueue.sameVideo(it.url, url)) return true;
        return false;
    }

    /** Video hinzufügen (oben bei "Später ansehen", sonst unten). */
    public static synchronized void add(String id, Item video) {
        Playlist p = get(id);
        if (p == null || video == null || video.url == null || contains(id, video.url)) return;
        Item copy = Item.fromJson(safeJson(video));
        copy.type = Item.VIDEO;
        if (WATCH_LATER.equals(id)) p.items.add(0, copy);
        else p.items.add(copy);
        save();
    }

    public static synchronized void remove(String id, String url) {
        Playlist p = get(id);
        if (p == null) return;
        for (int i = p.items.size() - 1; i >= 0; i--) {
            if (PlayQueue.sameVideo(p.items.get(i).url, url)) p.items.remove(i);
        }
        save();
    }

    public static synchronized void move(String id, int from, int to) {
        Playlist p = get(id);
        if (p == null || from < 0 || to < 0 || from >= p.items.size() || to >= p.items.size()) return;
        Item it = p.items.remove(from);
        p.items.add(to, it);
        save();
    }

    /** Für die Bibliothek: Playlist als Listeneintrag. */
    public static Item asItem(Playlist p) {
        Item it = new Item();
        it.type = Item.PLAYLIST;
        it.localPlaylistId = p.id;
        it.url = "local:" + p.id;
        it.title = p.name;
        it.thumb = p.items.isEmpty() ? null : p.items.get(0).thumb;
        it.extra = p.items.size() + " Videos · Auf diesem Gerät";
        return it;
    }

    private static JSONObject safeJson(Item it) {
        try {
            return it.toJson();
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
