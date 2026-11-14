package de.delorean23.tube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Warteschlange: was nach dem aktuellen Video kommt (Playlist, "Als Nächstes", Autoplay).
 * Alles läuft im Haupt-Thread.
 * Created by DeLorean23
 */
public final class PlayQueue {

    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    public interface Listener {
        void onQueueChanged();
    }

    private static final List<Item> items = new ArrayList<>();
    private static int index = -1;
    private static boolean shuffle;
    private static int repeat = REPEAT_OFF;
    private static String title;
    /** Zuletzt gespielte Videos (für Autoplay: nichts doppelt). */
    private static final Set<String> played = new HashSet<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private PlayQueue() {
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        for (Listener l : listeners) l.onQueueChanged();
    }

    // ------------------------------------------------------------ Lesen

    public static List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    public static int index() {
        return index;
    }

    public static int size() {
        return items.size();
    }

    public static Item current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    public static boolean shuffle() {
        return shuffle;
    }

    public static int repeat() {
        return repeat;
    }

    public static String title() {
        return title;
    }

    public static boolean hasNext() {
        return index + 1 < items.size() || (repeat == REPEAT_ALL && !items.isEmpty());
    }

    public static boolean hasPrevious() {
        return index > 0;
    }

    // ------------------------------------------------------------ Ändern

    /** Ein einzelnes Video öffnen: Warteschlange beginnt neu. */
    public static void playSingle(Item it) {
        CarLibrary.audioOnly = false;
        items.clear();
        items.add(it);
        index = 0;
        title = null;
        markPlayed(it);
        changed();
    }

    /** Eine ganze Liste abspielen (Playlist), ab Position start. */
    public static void playList(List<Item> list, int start, boolean shuffled, String name) {
        CarLibrary.audioOnly = false;
        items.clear();
        List<Item> l = new ArrayList<>();
        for (Item it : list) if (it.type == Item.VIDEO && it.url != null) l.add(it);
        if (l.isEmpty()) return;
        start = Math.max(0, Math.min(start, l.size() - 1));
        if (shuffled) {
            Item first = l.remove(start);
            Collections.shuffle(l, new Random());
            l.add(0, first);
            start = 0;
        }
        items.addAll(l);
        index = start;
        shuffle = shuffled;
        title = name;
        markPlayed(current());
        changed();
    }

    /** Das gerade laufende Video ist ein anderes geworden (z. B. Video in der Liste angetippt). */
    public static void ensureCurrent(Item it) {
        Item cur = current();
        if (cur != null && sameVideo(cur.url, it.url)) return;
        for (int i = 0; i < items.size(); i++) {
            if (sameVideo(items.get(i).url, it.url)) {
                index = i;
                markPlayed(it);
                changed();
                return;
            }
        }
        playSingle(it);
    }

    /** Wiedergabe beendet (Mini-Player geschlossen): Warteschlange leeren. */
    public static void clear() {
        items.clear();
        index = -1;
        title = null;
        changed();
    }

    public static void playNext(Item it) {
        if (items.isEmpty()) {
            playSingle(it);
            return;
        }
        items.add(index + 1, it);
        changed();
    }

    public static void append(Item it) {
        if (items.isEmpty()) {
            playSingle(it);
            return;
        }
        items.add(it);
        changed();
    }

    public static void remove(int pos) {
        if (pos < 0 || pos >= items.size() || pos == index) return;
        items.remove(pos);
        if (pos < index) index--;
        changed();
    }

    public static void move(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size() || from == to) return;
        Item it = items.remove(from);
        items.add(to, it);
        if (index == from) index = to;
        else if (from < index && to >= index) index--;
        else if (from > index && to <= index) index++;
        changed();
    }

    /** Zu Eintrag springen; liefert das Video oder null. */
    public static Item jump(int pos) {
        if (pos < 0 || pos >= items.size()) return null;
        index = pos;
        markPlayed(current());
        changed();
        return current();
    }

    /** Nächstes Video (null = Ende der Liste). */
    public static Item next() {
        if (items.isEmpty()) return null;
        if (index + 1 < items.size()) {
            index++;
        } else if (repeat == REPEAT_ALL) {
            index = 0;
        } else {
            return null;
        }
        markPlayed(current());
        changed();
        return current();
    }

    public static Item previous() {
        if (index <= 0) return null;
        index--;
        changed();
        return current();
    }

    public static void setShuffle(boolean s) {
        if (s == shuffle) return;
        shuffle = s;
        if (s && index + 1 < items.size()) {
            // nur den Rest nach dem aktuellen Video mischen
            List<Item> rest = new ArrayList<>(items.subList(index + 1, items.size()));
            Collections.shuffle(rest, new Random());
            while (items.size() > index + 1) items.remove(items.size() - 1);
            items.addAll(rest);
        }
        changed();
    }

    public static void setRepeat(int r) {
        repeat = r;
        changed();
    }

    /** Liefert ein Video für Autoplay aus den Vorschlägen, das noch nicht lief. */
    public static Item pickAutoplay(List<Item> related) {
        for (Item it : related) {
            if (it.type != Item.VIDEO || it.live || it.shorts || it.url == null) continue;
            String id = Util.videoId(it.url);
            if (id != null && !played.contains(id)) return it;
        }
        return null;
    }

    private static void markPlayed(Item it) {
        if (it == null) return;
        String id = Util.videoId(it.url);
        if (id != null) played.add(id);
        if (played.size() > 500) played.clear();
    }

    public static boolean sameVideo(String a, String b) {
        String ia = Util.videoId(a), ib = Util.videoId(b);
        return ia != null && ia.equals(ib);
    }
}
