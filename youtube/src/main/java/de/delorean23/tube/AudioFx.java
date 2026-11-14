package de.delorean23.tube;

import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;

/**
 * Klang: Equalizer, Bass-Boost und "Lautstärke angleichen" (leise Stellen lauter, laute leiser).
 * Hängt an der Tonausgabe des Players. Einstellungen bleiben gespeichert.
 * Created by DeLorean23
 */
public final class AudioFx {

    private static Equalizer eq;
    private static BassBoost bass;
    private static Object dyn; // DynamicsProcessing (ab Android 9)
    private static int session;

    private AudioFx() {
    }

    private static SharedPreferences prefs() {
        return Playback.prefs();
    }

    public static boolean eqOn() {
        return prefs().getBoolean("fx_eq", false);
    }

    public static boolean normalizeOn() {
        return prefs().getBoolean("fx_norm", false);
    }

    public static int bassStrength() {
        return prefs().getInt("fx_bass", 0);
    }

    public static int preset() {
        return prefs().getInt("fx_preset", -1);
    }

    public static int bandLevel(int band) {
        return prefs().getInt("fx_band" + band, 0);
    }

    public static boolean normalizeSupported() {
        return Build.VERSION.SDK_INT >= 28;
    }

    /** Equalizer des Players (oder null, wenn gerade keiner läuft). */
    public static Equalizer equalizer() {
        return eq;
    }

    /** Neue Tonausgabe des Players: Effekte daran hängen. */
    public static synchronized void attach(int audioSession) {
        if (audioSession == session && eq != null) {
            apply();
            return;
        }
        release();
        session = audioSession;
        if (audioSession == 0) return;
        try {
            eq = new Equalizer(1000, audioSession);
        } catch (Throwable e) {
            eq = null;
        }
        try {
            bass = new BassBoost(1000, audioSession);
        } catch (Throwable e) {
            bass = null;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                dyn = createDynamics(audioSession);
            } catch (Throwable e) {
                dyn = null;
            }
        }
        apply();
    }

    @androidx.annotation.RequiresApi(28)
    private static Object createDynamics(int s) {
        // 1 Kompressor-Band über alles + Begrenzer: gleicht die Lautstärke an
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, true, 1, false, 0, true);
        DynamicsProcessing.Config cfg = b.build();
        for (int ch = 0; ch < 2; ch++) {
            DynamicsProcessing.MbcBand band = cfg.getChannelByChannelIndex(ch).getMbcBand(0);
            band.setEnabled(true);
            band.setCutoffFrequency(20000);
            band.setThreshold(-28f);
            band.setRatio(4f);
            band.setAttackTime(10f);
            band.setReleaseTime(250f);
            band.setKneeWidth(6f);
            band.setPostGain(10f);
            cfg.getChannelByChannelIndex(ch).setMbcBand(0, band);
            DynamicsProcessing.Limiter lim = cfg.getChannelByChannelIndex(ch).getLimiter();
            lim.setEnabled(true);
            lim.setThreshold(-2f);
            lim.setRatio(10f);
            lim.setAttackTime(1f);
            lim.setReleaseTime(60f);
            cfg.getChannelByChannelIndex(ch).setLimiter(lim);
        }
        return new DynamicsProcessing(1000, s, cfg);
    }

    /** Gespeicherte Einstellungen anwenden. */
    public static synchronized void apply() {
        try {
            if (eq != null) {
                eq.setEnabled(eqOn());
                if (eqOn()) {
                    int p = preset();
                    if (p >= 0 && p < eq.getNumberOfPresets()) {
                        eq.usePreset((short) p);
                    } else {
                        short[] range = eq.getBandLevelRange();
                        for (short b = 0; b < eq.getNumberOfBands(); b++) {
                            int lvl = Math.max(range[0], Math.min(range[1], bandLevel(b)));
                            eq.setBandLevel(b, (short) lvl);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (bass != null) {
                int st = bassStrength();
                bass.setEnabled(st > 0);
                if (st > 0 && bass.getStrengthSupported()) bass.setStrength((short) st);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 28 && dyn != null) {
                ((DynamicsProcessing) dyn).setEnabled(normalizeOn());
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void release() {
        try {
            if (eq != null) eq.release();
        } catch (Throwable ignored) {
        }
        try {
            if (bass != null) bass.release();
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 28 && dyn != null) ((DynamicsProcessing) dyn).release();
        } catch (Throwable ignored) {
        }
        eq = null;
        bass = null;
        dyn = null;
        session = 0;
    }
}
