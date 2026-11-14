package de.delorean23.tube;

import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

/**
 * SponsorBlock: von der Community markierte Abschnitte (Werbung, Abo-Aufrufe, Intros …)
 * automatisch überspringen. Datenschutz: es wird nur der Anfang eines Hashes der Video-ID gesendet.
 * Created by DeLorean23
 */
public final class SponsorBlock {

    public static final String[] CATEGORIES = {"sponsor", "selfpromo", "interaction", "intro", "outro",
            "preview", "music_offtopic", "filler"};
    public static final String[] LABELS = {"Werbung / Sponsor", "Eigenwerbung", "Abo-/Like-Aufforderung",
            "Intro", "Abspann", "Vorschau / Rückblick", "Nicht-Musik-Teil in Musikvideos", "Abschweifungen"};
    private static final boolean[] DEFAULTS = {true, true, true, false, false, false, false, false};

    public static final class Segment {
        public final long startMs, endMs;
        public final String category;

        Segment(long s, long e, String c) {
            startMs = s;
            endMs = e;
            category = c;
        }
    }

    private static final LruCache<String, List<Segment>> CACHE = new LruCache<>(50);

    private SponsorBlock() {
    }

    public static boolean enabled() {
        return Playback.prefs().getBoolean("sb_on", true);
    }

    public static boolean categoryOn(int i) {
        return Playback.prefs().getBoolean("sb_" + CATEGORIES[i], DEFAULTS[i]);
    }

    public static void setCategory(int i, boolean on) {
        Playback.prefs().edit().putBoolean("sb_" + CATEGORIES[i], on).apply();
        CACHE.evictAll();
    }

    public static String label(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(category)) return LABELS[i];
        return category;
    }

    /** Abschnitte zum Überspringen (nicht im UI-Thread aufrufen). */
    public static List<Segment> segments(String videoId) {
        List<Segment> out = new ArrayList<>();
        if (videoId == null || !enabled()) return out;
        List<Segment> cached = CACHE.get(videoId);
        if (cached != null) return cached;
        try {
            JSONArray cats = new JSONArray();
            for (int i = 0; i < CATEGORIES.length; i++) if (categoryOn(i)) cats.put(CATEGORIES[i]);
            if (cats.length() == 0) return out;
            String prefix = sha256(videoId).substring(0, 4);
            String url = "https://sponsor.ajay.app/api/skipSegments/" + prefix
                    + "?categories=" + URLEncoder.encode(cats.toString(), "UTF-8");
            Request req = new Request.Builder().url(url).header("User-Agent", "DeLoreanTube").build();
            try (Response r = NetDownloader.get().client().newCall(req).execute()) {
                if (r.code() == 404) {
                    CACHE.put(videoId, out); // keine Abschnitte
                    return out;
                }
                if (!r.isSuccessful() || r.body() == null) return out;
                JSONArray a = new JSONArray(r.body().string());
                for (int i = 0; i < a.length(); i++) {
                    JSONObject v = a.getJSONObject(i);
                    if (!videoId.equals(v.optString("videoID"))) continue;
                    JSONArray segs = v.optJSONArray("segments");
                    if (segs == null) continue;
                    for (int j = 0; j < segs.length(); j++) {
                        JSONObject s = segs.getJSONObject(j);
                        if (!"skip".equals(s.optString("actionType", "skip"))) continue;
                        JSONArray t = s.optJSONArray("segment");
                        if (t == null || t.length() < 2) continue;
                        long st = (long) (t.getDouble(0) * 1000), en = (long) (t.getDouble(1) * 1000);
                        if (en - st >= 1000) out.add(new Segment(st, en, s.optString("category")));
                    }
                }
            }
            CACHE.put(videoId, out);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    // ------------------------------------------------------------ Dislikes (Return YouTube Dislike)

    public static boolean showDislikes() {
        return Playback.prefs().getBoolean("ryd_on", true);
    }

    /** Geschätzte Dislikes, -1 = unbekannt. Nicht im UI-Thread aufrufen. */
    public static long dislikes(String videoId) {
        if (videoId == null || !showDislikes()) return -1;
        try (Response r = NetDownloader.get().client().newCall(new Request.Builder()
                .url("https://returnyoutubedislikeapi.com/votes?videoId=" + videoId)
                .header("User-Agent", "DeLoreanTube").build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return -1;
            return new JSONObject(r.body().string()).optLong("dislikes", -1);
        } catch (Throwable e) {
            return -1;
        }
    }
}
