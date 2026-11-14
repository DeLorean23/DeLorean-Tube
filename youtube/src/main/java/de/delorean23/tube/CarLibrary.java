package de.delorean23.tube;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mediathek für Android Auto (nur Ton): Später ansehen, Downloads, Verlauf, Playlists,
 * Abo-Feed, Mag ich und Suche. Ein angetipptes Video spielt die ganze Liste ab dort.
 * Created by DeLorean23
 */
public final class CarLibrary {

    static final String ROOT = "root", WL = "wl", DL = "dl", HIST = "hist", PL = "pl",
            FEED = "feed", LIKED = "liked", LOCAL_PL = "lpl:", ACCOUNT_PL = "apl:", SEARCH = "search:";

    /** Ein Video aus einer Liste: parent \t index \t url */
    private static final String SEP = "\t";

    /** Zuletzt geladene Videos je Ordner (für Warteschlange und onGetItem). */
    private static final Map<String, List<Item>> lists = Collections.synchronizedMap(new HashMap<>());

    /** true, solange die Wiedergabe von Android Auto gestartet wurde: dann nur Ton laden. */
    static volatile boolean audioOnly;

    private CarLibrary() {
    }

    static MediaItem root() {
        return folder(ROOT, "DeLorean Tube", false);
    }

    private static MediaItem folder(String id, String title, boolean playable) {
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(title).setDisplayTitle(title)
                .setIsBrowsable(true).setIsPlayable(playable)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build();
    }

    private static MediaItem video(String parent, int index, Item it) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(it.title).setDisplayTitle(it.title)
                .setArtist(it.uploader).setSubtitle(it.uploader)
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC);
        if (it.thumb != null) md.setArtworkUri(Uri.parse(it.thumb));
        return new MediaItem.Builder()
                .setMediaId(parent + SEP + index + SEP + it.url)
                .setMediaMetadata(md.build()).build();
    }

    /** Nicht im UI-Thread aufrufen. */
    static List<MediaItem> children(String parent) throws Exception {
        List<MediaItem> out = new ArrayList<>();
        if (ROOT.equals(parent)) {
            out.add(folder(WL, "Später ansehen", true));
            out.add(folder(DL, "Downloads", true));
            out.add(folder(HIST, "Verlauf", true));
            out.add(folder(PL, "Playlists", false));
            if (Account.isLoggedIn()) {
                out.add(folder(FEED, "Abos", true));
                out.add(folder(LIKED, "Mag ich", true));
            }
            return out;
        }
        if (PL.equals(parent)) {
            for (LocalPlaylists.Playlist p : LocalPlaylists.all()) {
                if (LocalPlaylists.WATCH_LATER.equals(p.id)) continue;
                out.add(folder(LOCAL_PL + p.id, p.name, true));
            }
            if (Account.isLoggedIn()) {
                try {
                    for (Item p : Account.myPlaylists()) {
                        if (p.apiPlaylistId != null) out.add(folder(ACCOUNT_PL + p.apiPlaylistId, p.title, true));
                    }
                } catch (Throwable ignored) {
                }
            }
            return out;
        }
        List<Item> items = load(parent);
        lists.put(parent, items);
        for (int i = 0; i < items.size(); i++) out.add(video(parent, i, items.get(i)));
        return out;
    }

    static List<Item> load(String parent) throws Exception {
        List<Item> raw = new ArrayList<>();
        if (WL.equals(parent)) {
            LocalPlaylists.Playlist p = LocalPlaylists.get(LocalPlaylists.WATCH_LATER);
            if (p != null) raw.addAll(p.items);
        } else if (DL.equals(parent)) {
            for (Downloads.Entry e : Downloads.all()) {
                if (Downloads.exists(App.get(), e)) raw.add(e.asItem());
            }
        } else if (HIST.equals(parent)) {
            raw.addAll(History.all());
        } else if (FEED.equals(parent)) {
            raw.addAll(Feed.load(false));
        } else if (LIKED.equals(parent)) {
            raw.addAll(Account.likedVideos());
        } else if (parent.startsWith(LOCAL_PL)) {
            LocalPlaylists.Playlist p = LocalPlaylists.get(parent.substring(LOCAL_PL.length()));
            if (p != null) raw.addAll(p.items);
        } else if (parent.startsWith(ACCOUNT_PL)) {
            raw.addAll(Account.playlistItems(parent.substring(ACCOUNT_PL.length())));
        } else if (parent.startsWith(SEARCH)) {
            List<Item> cached = lists.get(parent);
            if (cached != null) return cached;
        }
        List<Item> out = new ArrayList<>();
        for (Item it : raw) {
            if (it.type == Item.VIDEO && it.url != null && !it.live && out.size() < 200) out.add(it);
        }
        return out;
    }

    @Nullable
    static MediaItem item(String mediaId) {
        if (ROOT.equals(mediaId)) return root();
        String[] p = mediaId.split(SEP, 3);
        if (p.length == 3) {
            List<Item> l = lists.get(p[0]);
            try {
                int i = Integer.parseInt(p[1]);
                if (l != null && i < l.size()) return video(p[0], i, l.get(i));
            } catch (NumberFormatException ignored) {
            }
            Item it = new Item();
            it.url = p[2];
            return video(p[0], 0, it);
        }
        return null;
    }

    /** Sucht bei YouTube; Ergebnis-Anzahl. Nicht im UI-Thread aufrufen. */
    static int search(String query) {
        List<Item> out = new ArrayList<>();
        try {
            SearchInfo info = SearchInfo.getInfo(ServiceList.YouTube,
                    ServiceList.YouTube.getSearchQHFactory().fromQuery(query, Collections.emptyList(), ""));
            for (InfoItem ii : info.getRelatedItems()) {
                Item it = Item.from(ii);
                if (it != null && it.type == Item.VIDEO && !it.live && !it.shorts) out.add(it);
            }
        } catch (Throwable ignored) {
        }
        lists.put(SEARCH + query, out);
        return out.size();
    }

    static List<MediaItem> searchResult(String query) {
        List<MediaItem> out = new ArrayList<>();
        List<Item> l = lists.get(SEARCH + query);
        if (l != null) for (int i = 0; i < l.size(); i++) out.add(video(SEARCH + query, i, l.get(i)));
        return out;
    }

    /**
     * Android Auto hat ein Video (oder einen ganzen Ordner) angetippt:
     * Warteschlange = die ganze Liste ab diesem Video.
     */
    static void queueFor(String mediaId) {
        String parent;
        int index = 0;
        String[] p = mediaId.split(SEP, 3);
        if (p.length == 3) {
            parent = p[0];
            try {
                index = Integer.parseInt(p[1]);
            } catch (NumberFormatException ignored) {
            }
        } else if (!mediaId.contains("\n")) {
            parent = mediaId; // ganzer Ordner abspielen
            try {
                lists.put(parent, load(parent));
            } catch (Throwable ignored) {
            }
        } else {
            return;
        }
        List<Item> l = lists.get(parent);
        if (l == null || l.isEmpty()) return;
        PlayQueue.playList(l, Math.min(index, l.size() - 1), false, "Android Auto");
        audioOnly = true;
    }

    /** Sprachsuche: Ergebnisse als Warteschlange. */
    static void playFromSearch(String query) {
        if (search(query) > 0) queueFor(SEARCH + query + SEP + "0" + SEP + "-");
    }

    /** Eintrag aus der Mediathek in eine abspielbare Anfrage (nur Ton) umwandeln. */
    static MediaItem toPlayable(MediaItem mi) {
        String id = mi.mediaId;
        String url = null;
        String[] p = id.split(SEP, 3);
        if (p.length == 3) {
            url = p[2];
        } else if (!id.contains("\n")) {
            Item cur = PlayQueue.current(); // ganzer Ordner: queueFor hat die Warteschlange gesetzt
            if (cur != null) url = cur.url;
        }
        if (url == null) return mi; // schon im eigenen Format (aus der App)
        Item cur = PlayQueue.current();
        MediaMetadata.Builder md = mi.mediaMetadata.buildUpon();
        if (cur != null && url.equals(cur.url)) {
            if (cur.title != null) md.setTitle(cur.title);
            if (cur.uploader != null) md.setArtist(cur.uploader);
        }
        return new MediaItem.Builder().setMediaId(TubeSources.mediaId(url, 0, true))
                .setMediaMetadata(md.build()).build();
    }
}
