package de.delorean23.tube;

/**
 * Schlaf-Timer: stoppt die Wiedergabe nach einer Zeit oder am Ende des Videos.
 * Created by DeLorean23
 */
public final class SleepTimer {

    private static long endsAt;
    private static boolean endOfVideo;
    private static final Runnable FIRE = SleepTimer::fire;

    private SleepTimer() {
    }

    public static void start(int minutes) {
        cancel();
        endsAt = System.currentTimeMillis() + minutes * 60_000L;
        App.MAIN.postDelayed(FIRE, minutes * 60_000L);
    }

    public static void atEndOfVideo() {
        cancel();
        endOfVideo = true;
    }

    public static void cancel() {
        App.MAIN.removeCallbacks(FIRE);
        endsAt = 0;
        endOfVideo = false;
    }

    public static boolean active() {
        return endsAt > 0 || endOfVideo;
    }

    /** Wird vom Player am Videoende gefragt: true = jetzt stoppen statt weiterspielen. */
    public static boolean consumeEndOfVideo() {
        if (!endOfVideo) return false;
        endOfVideo = false;
        return true;
    }

    /** Text für den Knopf, z. B. "23 Min." */
    public static String label() {
        if (endOfVideo) return "Videoende";
        if (endsAt <= 0) return "Timer";
        long left = Math.max(0, endsAt - System.currentTimeMillis());
        long min = (left + 59_999) / 60_000;
        return min + " Min.";
    }

    private static void fire() {
        endsAt = 0;
        PlaybackService.pauseFromTimer();
    }
}
