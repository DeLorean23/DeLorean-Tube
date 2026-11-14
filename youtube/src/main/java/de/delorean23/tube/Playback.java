package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.Player;

/** Gemeinsame Player-Einstellungen. Created by DeLorean23 */
public final class Playback {

    private Playback() {
    }

    public static SharedPreferences prefs() {
        return App.get().getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    public static boolean audioOnly() {
        return prefs().getBoolean("audio_only", false);
    }

    /** Nach dem Video automatisch ein passendes nächstes abspielen. */
    public static boolean autoplay() {
        return prefs().getBoolean("autoplay", true);
    }

    /** Videos dort weiterspielen, wo man aufgehört hat. */
    public static boolean resume() {
        return prefs().getBoolean("resume", true);
    }

    /** Beim Verlassen der App automatisch Bild-im-Bild. */
    public static boolean pip() {
        return prefs().getBoolean("pip", true);
    }

    /** Shorts im Abo-Feed ausblenden. */
    public static boolean hideShortsInFeed() {
        return prefs().getBoolean("hide_shorts_feed", false);
    }

    /** Suchverlauf speichern. */
    public static boolean saveSearches() {
        return prefs().getBoolean("save_searches", true);
    }

    /**
     * Untertitel: "" = aus, sonst Sprachcode (z. B. "de", "en").
     * "de-tr" = automatisch auf Deutsch übersetzt.
     */
    public static String subtitles() {
        return prefs().getString("subtitles", "");
    }

    public static void setBool(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    /** Gewünschte Videohöhe je nach WLAN / mobile Daten. */
    public static int wantedHeight(Context c) {
        boolean wifi = Util.isOnWifi(c);
        return prefs().getInt(wifi ? "height_wifi" : "height_mobile", wifi ? 720 : 480);
    }

    /**
     * Bild an/aus. visible=false wenn kein Bildschirm der App mehr sichtbar ist:
     * dann wird nur noch der Ton geladen (spart Daten und Akku, Ton läuft weiter).
     */
    public static void setVideo(Player p, boolean visible) {
        if (p == null) return;
        try {
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !visible || audioOnly())
                    .build());
        } catch (Throwable ignored) {
        }
    }
}
