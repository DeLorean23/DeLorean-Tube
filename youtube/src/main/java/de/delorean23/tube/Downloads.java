package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Liste der heruntergeladenen Dateien (für die Downloads-Seite). Created by DeLorean23 */
public final class Downloads {

    public static final class Entry {
        public String title, artist, uri, mime, videoUrl, thumb, folder;
        public long size, date;

        public boolean isAudio() {
            return mime != null && mime.startsWith("audio/");
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("t", title).putOpt("a", artist).put("u", uri).put("m", mime)
                    .putOpt("v", videoUrl).putOpt("th", thumb).putOpt("f", folder)
                    .put("s", size).put("d", date);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.title = o.optString("t");
            e.artist = o.optString("a", null);
            e.uri = o.optString("u");
            e.mime = o.optString("m");
            e.videoUrl = o.optString("v", null);
            e.thumb = o.optString("th", null);
            e.folder = o.optString("f", null);
            e.size = o.optLong("s");
            e.date = o.optLong("d");
            return e;
        }

        /** Als Listeneintrag; url "local:<uri>" spielt die Datei im eigenen Player ab. */
        public Item asItem() {
            Item it = new Item();
            it.type = Item.VIDEO;
            it.url = "local:" + uri;
            it.title = title;
            it.thumb = thumb;
            StringBuilder sb = new StringBuilder(isAudio() ? "MP3" : "MP4");
            if (size > 0) sb.append(" · ").append(String.format(java.util.Locale.GERMANY, "%.1f MB", size / 1048576.0));
            if (folder != null) sb.append(" · ").append(folder);
            else if (artist != null) sb.append(" · ").append(artist);
            it.uploader = sb.toString();
            return it;
        }
    }

    private Downloads() {
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences("downloads", Context.MODE_PRIVATE);
    }

    public static synchronized List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void add(Entry e) {
        List<Entry> l = all();
        l.add(0, e);
        save(l);
    }

    public static synchronized void remove(String uri) {
        List<Entry> l = all();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).uri.equals(uri)) l.remove(i);
        save(l);
    }

    public static synchronized Entry find(String uri) {
        for (Entry e : all()) if (e.uri.equals(uri)) return e;
        return null;
    }

    private static void save(List<Entry> l) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : l) a.put(e.toJson());
            prefs().edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Datei noch vorhanden? (vom Nutzer gelöscht oder verschoben = weg) */
    public static boolean exists(Context c, Entry e) {
        try {
            Uri u = Uri.parse(e.uri);
            if ("file".equals(u.getScheme())) return new java.io.File(u.getPath()).exists();
            try (android.database.Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
                return cur != null && cur.getCount() > 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
