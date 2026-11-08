package de.delorean23.cloud;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Game list from the public Xbox Game Pass catalog (all cloud games, Germany,
 * German names). No sign in needed. Cached in the app folder.
 */
public class Catalog {

    public static class Game {
        public String storeId, titleId, name, poster, desc, publisher, genre;
        public boolean touch;
    }

    public interface Progress { void step(String text); }

    private static final String BASE = "https://catalog.gamepass.com";
    private static final String CLOUD_LIST = "29a81209-df6f-41fd-a528-2ae6b91f719c";
    private static final String MARKET = "DE", LANG = "de-DE";
    private static final long MAX_AGE = 12L * 60 * 60 * 1000;   // 12 h

    private final File cache;

    public Catalog(Context c) {
        cache = new File(c.getFilesDir(), "catalog.json");
    }

    public boolean hasCache() { return cache.isFile() && cache.length() > 0; }

    public boolean cacheIsOld() {
        return !hasCache() || System.currentTimeMillis() - cache.lastModified() > MAX_AGE;
    }

    public List<Game> loadCache() {
        try {
            return parse(new JSONObject(readFile(cache)));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Downloads the full list (runs in a background thread). */
    public List<Game> download(Progress p) throws Exception {
        p.step("Lade Liste der Cloud-Spiele...");
        JSONArray list = new JSONArray(http(BASE + "/sigls/v2?id=" + CLOUD_LIST
                + "&market=" + MARKET + "&language=" + LANG, null));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && o.has("id")) ids.add(o.getString("id"));
        }
        if (ids.isEmpty()) throw new Exception("Leere Liste");

        JSONObject all = new JSONObject();
        for (int i = 0; i < ids.size(); i += 100) {
            p.step("Lade Spiele " + (i + 1) + " bis " + Math.min(i + 100, ids.size())
                    + " von " + ids.size() + "...");
            JSONArray batch = new JSONArray();
            for (String id : ids.subList(i, Math.min(i + 100, ids.size()))) batch.put(id);
            JSONObject body = new JSONObject().put("Products", batch);
            JSONObject res = new JSONObject(http(BASE + "/v3/products?market=" + MARKET
                    + "&language=" + LANG + "&hydration=RemoteHighSapphire0", body.toString()));
            JSONObject prods = res.optJSONObject("Products");
            if (prods == null) continue;
            Iterator<String> it = prods.keys();
            while (it.hasNext()) {
                String k = it.next();
                all.put(k, slim(k, prods.getJSONObject(k)));
            }
        }
        List<Game> games = parse(all);
        if (games.isEmpty()) throw new Exception("Keine Spiele gefunden");
        writeFile(cache, all.toString());
        return games;
    }

    // keeps only what the app needs (small cache file)
    private static JSONObject slim(String storeId, JSONObject p) throws Exception {
        JSONObject o = new JSONObject();
        o.put("storeId", p.optString("StoreId", storeId));
        o.put("titleId", p.optString("XCloudTitleId", ""));
        o.put("name", p.optString("ProductTitle", storeId));
        o.put("desc", p.optString("ProductDescriptionShort", p.optString("ProductDescription", "")));
        o.put("publisher", p.optString("PublisherName", ""));
        JSONArray cats = p.optJSONArray("LocalizedCategories");
        if (cats == null) cats = p.optJSONArray("Categories");
        StringBuilder g = new StringBuilder();
        if (cats != null) for (int i = 0; i < cats.length() && i < 2; i++) {
            if (g.length() > 0) g.append(", ");
            g.append(cats.optString(i));
        }
        o.put("genre", g.toString());
        JSONObject img = p.optJSONObject("Image_Poster");
        if (img == null) img = p.optJSONObject("Image_Tile");
        String url = img != null ? img.optString("URL", "") : "";
        if (url.startsWith("//")) url = "https:" + url;
        o.put("poster", url);

        boolean gamePass = false, touch = false;
        JSONObject off = p.optJSONObject("XCloudOfferings");
        if (off == null || off.length() == 0) gamePass = true;
        else {
            Iterator<String> it = off.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.toUpperCase(Locale.ROOT).startsWith("XGPU")) gamePass = true;
                JSONObject v = off.optJSONObject(k);
                JSONArray in = v != null ? v.optJSONArray("SupportedInputTypes") : null;
                if (in != null) for (int i = 0; i < in.length(); i++) {
                    String t = in.optString(i).toLowerCase(Locale.ROOT);
                    if (t.contains("touch")) touch = true;
                }
            }
        }
        o.put("gamePass", gamePass);
        o.put("touch", touch);
        return o;
    }

    private static List<Game> parse(JSONObject all) {
        List<Game> out = new ArrayList<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            JSONObject o = all.optJSONObject(it.next());
            if (o == null || !o.optBoolean("gamePass") || o.optString("titleId").isEmpty()) continue;
            Game g = new Game();
            g.storeId = o.optString("storeId");
            g.titleId = o.optString("titleId");
            g.name = clean(o.optString("name"));
            g.poster = o.optString("poster");
            g.desc = o.optString("desc");
            g.publisher = o.optString("publisher");
            g.genre = o.optString("genre");
            g.touch = o.optBoolean("touch");
            out.add(g);
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static String clean(String s) {
        return s.replaceAll("[™®©]", "").replaceAll("\\s+", " ").trim();
    }

    /** Link that starts the game straight into the stream. */
    public static String launchUrl(Game g) {
        String slug = g.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "game";
        return "https://www.xbox.com/de-DE/play/launch/" + slug + "/" + g.storeId;
    }

    // ------------------------------------------------------------------ io
    private static String http(String url, String postBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("ms-cv", "0");
        c.setRequestProperty("calling-app-name", "Xbox Cloud Gaming Web");
        c.setRequestProperty("calling-app-version", "21.0.0");
        if (postBody != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(postBody.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static void writeFile(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new Exception("Cache");
    }
}
