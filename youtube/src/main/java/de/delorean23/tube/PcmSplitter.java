package de.delorean23.tube;

/**
 * Verteilt dekodierte Ton-Puffer auf Teile (Kapitel/Abschnitt), auf das Sample genau.
 * Reine Rechenlogik ohne Android, damit sie getestet werden kann.
 * Created by DeLorean23
 */
public final class PcmSplitter {

    public interface Sink {
        /** Frames [fStart, fEnd) dieses Puffers gehören zu Teil seg. */
        void write(int seg, int fStart, int fEnd) throws java.io.IOException;

        /** Teil seg ist fertig. */
        void close(int seg) throws java.io.IOException;
    }

    private final long[] starts, ends;
    private int k;

    /** Zeiten in Mikrosekunden; Long.MAX_VALUE = bis zum Ende. */
    public PcmSplitter(long[] starts, long[] ends) {
        this.starts = starts;
        this.ends = ends;
    }

    public boolean done() {
        return k >= starts.length;
    }

    public void feed(long bufStartUs, int frames, int sampleRate, Sink sink) throws java.io.IOException {
        double frameUs = 1_000_000.0 / sampleRate;
        int f = 0;
        while (f < frames && k < starts.length) {
            long s = starts[k], e = ends[k];
            long tFrame = bufStartUs + (long) Math.floor(f * frameUs);
            if (tFrame >= e) {
                sink.close(k);
                k++;
                continue;
            }
            int fStart = f;
            if (tFrame < s) {
                fStart = (int) Math.ceil((s - bufStartUs) / frameUs);
                if (fStart >= frames) return; // Teil fängt erst in einem späteren Puffer an
                if (fStart < f) fStart = f;
            }
            int fEnd = frames;
            if (e != Long.MAX_VALUE) {
                double x = (e - bufStartUs) / frameUs;
                if (x < frames) fEnd = (int) Math.max(fStart, Math.ceil(x));
            }
            if (fEnd > fStart) sink.write(k, fStart, fEnd);
            f = fEnd;
            if (fEnd < frames) {
                sink.close(k);
                k++;
            }
        }
    }

    /** Am Ende: alle offenen Teile schließen. */
    public void finish(Sink sink) throws java.io.IOException {
        while (k < starts.length) {
            sink.close(k);
            k++;
        }
    }
}
