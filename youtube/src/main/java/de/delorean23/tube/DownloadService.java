package de.delorean23.tube;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import de.delorean23.tube.lame.Mp3Encoder;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads im Hintergrund:
 * MP3 (mit Cover, Titel, Interpret, Album), MP4 in gewählter Auflösung,
 * Kapitel als einzelne MP3s, nur ein Abschnitt (von–bis) und ganze Playlists.
 * Gespeichert in Musik/DeLorean Tube bzw. Filme/DeLorean Tube (Playlists/Kapitel in eigenem Unterordner).
 * Created by DeLorean23
 */
public class DownloadService extends Service {

    public static final String MODE_FULL = "full", MODE_CHAPTERS = "chapters", MODE_SECTION = "section";

    private static final String FOLDER = "DeLorean Tube";
    private static final long CHUNK = 4L * 1024 * 1024;
    private static final int FG_ID = 4200;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger running = new AtomicInteger(0);
    private final AtomicInteger notifyIds = new AtomicInteger(4300);
    private NotificationManager nm;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification fg = base("Download wird vorbereitet …").setProgress(0, 0, true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, fg);
        }
        if (intent == null) {
            if (running.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        final Job job = new Job(notifyIds.getAndIncrement(), intent);
        running.incrementAndGet();
        worker.execute(() -> {
            try {
                job.run();
            } catch (Throwable e) {
                nm.notify(job.id, base("Download fehlgeschlagen")
                        .setContentText(job.title + ": " + e.getMessage())
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(job.title + "\n" + e.getMessage()))
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setOngoing(false).setAutoCancel(true).build());
            } finally {
                if (running.decrementAndGet() == 0) {
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private NotificationCompat.Builder base(String title) {
        return new NotificationCompat.Builder(this, App.CH_DOWNLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
    }

    /** Ein Teil der MP3-Ausgabe (ganzes Lied, ein Kapitel oder ein Abschnitt). */
    private static final class Seg {
        final long startUs, endUs;
        final String title;
        final int track;
        File file;
        OutputStream os;
        Mp3Encoder enc;

        Seg(long startUs, long endUs, String title, int track) {
            this.startUs = startUs;
            this.endUs = endUs;
            this.title = title;
            this.track = track;
        }
    }

    // ------------------------------------------------------------------ Auftrag

    private final class Job {
        final int id;
        final String url, title, artist, mode, folder, album;
        final boolean mp3;
        final int height, track;
        final long startMs, endMs;
        long lastNotify;
        int waiting;

        Job(int id, Intent i) {
            this.id = id;
            this.url = i.getStringExtra("url");
            String t = i.getStringExtra("title");
            this.title = t != null ? t : "Video";
            this.artist = i.getStringExtra("artist");
            this.mp3 = i.getBooleanExtra("mp3", true);
            this.height = i.getIntExtra("height", 720);
            String m = i.getStringExtra("mode");
            this.mode = m != null ? m : MODE_FULL;
            this.startMs = i.getLongExtra("startMs", 0);
            this.endMs = i.getLongExtra("endMs", 0);
            this.folder = i.getStringExtra("folder");
            this.album = i.getStringExtra("album");
            this.track = i.getIntExtra("track", 0);
        }

        void progress(String step, long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastNotify < 700 && done < total) return;
            lastNotify = now;
            int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
            int queued = running.get() - 1;
            Notification n = base(title)
                    .setContentText(step + (total > 0 ? "  " + pct + " %" : "")
                            + (queued > 0 ? "  ·  noch " + queued + " in der Warteschlange" : ""))
                    .setProgress(100, pct, total <= 0)
                    .build();
            nm.notify(FG_ID, n);
        }

        void run() throws Exception {
            progress("Lade Infos …", 0, 0);
            App.forgetStreamInfo(url); // frische Links holen
            StreamInfo info = App.streamInfo(url);
            File dir = new File(getCacheDir(), "dl");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String base = Util.safeFileName(title);
            String vid = Util.videoId(url);
            String thumb = vid != null ? "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg"
                    : Util.thumb(info.getThumbnails());
            String videoArtist = artist != null ? artist : info.getUploaderName();

            if (mp3) {
                AudioStream a = Streams.bestAudio(info, true);
                if (a == null) throw new IOException("Keine Tonspur gefunden");
                File src = new File(dir, id + "_audio.m4a");
                byte[] cover = cover(vid);

                // Was soll herauskommen?
                List<Seg> segs = new ArrayList<>();
                String outFolder = folder;
                String outAlbum = album;
                if (MODE_CHAPTERS.equals(mode) && info.getStreamSegments().size() >= 2) {
                    List<StreamSegment> ch = info.getStreamSegments();
                    for (int i = 0; i < ch.size(); i++) {
                        long s = ch.get(i).getStartTimeSeconds() * 1_000_000L;
                        long e = i + 1 < ch.size() ? ch.get(i + 1).getStartTimeSeconds() * 1_000_000L : Long.MAX_VALUE;
                        String t = ch.get(i).getTitle();
                        segs.add(new Seg(s, e, t != null && !t.isEmpty() ? t : "Teil " + (i + 1), i + 1));
                    }
                    outFolder = Util.safeFileName(title);
                    outAlbum = title;
                } else if (MODE_SECTION.equals(mode) && endMs > startMs) {
                    segs.add(new Seg(startMs * 1000, endMs * 1000,
                            title + " (" + Util.duration(startMs / 1000) + "–" + Util.duration(endMs / 1000) + ")",
                            track));
                } else {
                    segs.add(new Seg(0, Long.MAX_VALUE, title, track));
                }

                try {
                    download(a.getContent(), src, "Ton wird geladen", 0, 1);
                    for (int i = 0; i < segs.size(); i++) segs.get(i).file = new File(dir, id + "_" + i + ".mp3");
                    toMp3(src, segs, videoArtist, outAlbum, cover);
                    int saved = 0;
                    Uri last = null;
                    String lastName = null;
                    for (Seg s : segs) {
                        if (s.file == null || !s.file.exists() || s.file.length() < 1000) continue;
                        String name = (segs.size() > 1 ? String.format(Locale.GERMANY, "%02d - ", s.track) : "")
                                + Util.safeFileName(s.title) + ".mp3";
                        last = publish(s.file, name, "audio/mpeg", true, outFolder, s.title, videoArtist, outAlbum);
                        lastName = name;
                        register(s.title, videoArtist, last, "audio/mpeg", s.file.length(), thumb, outFolder);
                        saved++;
                    }
                    if (saved == 0) throw new IOException("Nichts gespeichert (Abschnitt leer?)");
                    if (saved == 1) finished("MP3 gespeichert", lastName, last, "audio/mpeg");
                    else finished(saved + " MP3s gespeichert", outFolder, null, null);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    src.delete();
                    for (Seg s : segs) {
                        //noinspection ResultOfMethodCallIgnored
                        if (s.file != null) s.file.delete();
                    }
                }
                return;
            }

            // ---------------- MP4
            VideoStream v = pickVideo(Streams.videoChoices(info, true), height);
            if (v == null) throw new IOException("Für dieses Video gibt es keine MP4-Datei");
            int h = Streams.height(v);
            boolean section = MODE_SECTION.equals(mode) && endMs > startMs;
            long cutStart = section ? startMs * 1000 : 0;
            long cutEnd = section ? endMs * 1000 : Long.MAX_VALUE;
            String name = base + (section ? " (" + Util.duration(startMs / 1000) + "–" + Util.duration(endMs / 1000) + ")" : "")
                    + " (" + Streams.label(v) + ").mp4";
            File out = new File(dir, id + ".mp4");
            File vf = new File(dir, id + "_video.mp4");
            File af = new File(dir, id + "_audio.m4a");
            try {
                if (!v.isVideoOnly()) {
                    // fertige Datei mit Ton
                    download(v.getContent(), vf, "Video wird geladen", 0, 1);
                    if (section) {
                        progress("Wird geschnitten …", 0, 0);
                        mux(vf, vf, out, cutStart, cutEnd);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        vf.renameTo(out);
                    }
                } else {
                    AudioStream a = Streams.bestAudio(info, true);
                    if (a == null) throw new IOException("Keine Tonspur gefunden");
                    download(v.getContent(), vf, "Bild wird geladen", 0, 2);
                    download(a.getContent(), af, "Ton wird geladen", 1, 2);
                    progress("Wird zusammengefügt …", 0, 0);
                    mux(vf, af, out, cutStart, cutEnd);
                }
                Uri uri = publish(out, name, "video/mp4", false, folder, title, videoArtist, album);
                register(title, videoArtist, uri, "video/mp4", out.length(), thumb, folder);
                finished("Video gespeichert (" + h + "p)", name, uri, "video/mp4");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                vf.delete();
                //noinspection ResultOfMethodCallIgnored
                af.delete();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        }

        /** Gewünschte Höhe oder die beste darunter (bei Playlists), sonst die kleinste. */
        VideoStream pickVideo(LinkedHashMap<Integer, VideoStream> choices, int wanted) {
            if (choices.isEmpty()) return null;
            VideoStream exact = choices.get(wanted);
            if (exact != null) return exact;
            VideoStream smallest = null;
            for (Map.Entry<Integer, VideoStream> e : choices.entrySet()) {
                smallest = e.getValue();
                if (e.getKey() <= wanted) return e.getValue(); // absteigend sortiert
            }
            return smallest;
        }

        /** Cover fürs MP3: Vorschaubild quadratisch zugeschnitten. */
        byte[] cover(String vid) {
            if (vid == null) return null;
            for (String q : new String[]{"maxresdefault", "sddefault", "hqdefault"}) {
                try (Response r = NetDownloader.get().client().newCall(new Request.Builder()
                        .url("https://i.ytimg.com/vi/" + vid + "/" + q + ".jpg").build()).execute()) {
                    if (!r.isSuccessful() || r.body() == null) continue;
                    byte[] data = r.body().bytes();
                    Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (b == null) continue;
                    int w = b.getWidth(), h = b.getHeight();
                    // 4:3-Bilder haben oben/unten schwarze Balken: nur den 16:9-Inhalt nehmen
                    int contentH = Math.abs(w * 3 - h * 4) < w / 10 ? w * 9 / 16 : h;
                    int side = Math.min(w, contentH);
                    Bitmap sq = Bitmap.createBitmap(b, (w - side) / 2, (h - side) / 2, side, side);
                    if (side > 600) sq = Bitmap.createScaledBitmap(sq, 600, 600, true);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    sq.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                    return bos.toByteArray();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        /** Lädt in 4-MB-Stücken (so drosselt YouTube nicht). */
        void download(String mediaUrl, File target, String step, int part, int parts) throws IOException {
            long total = -1;
            long pos = 0;
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[64 * 1024];
                while (total < 0 || pos < total) {
                    long end = pos + CHUNK - 1;
                    if (total > 0) end = Math.min(end, total - 1);
                    final long chunkStart = pos;
                    int attempt = 0;
                    while (true) {
                        try (Response r = NetDownloader.get().client()
                                .newCall(NetDownloader.get().mediaRequest(mediaUrl, pos, end)).execute()) {
                            if (r.code() == 416 && total < 0) {
                                total = pos; // genau am Ende
                                break;
                            }
                            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
                            String cr = r.header("Content-Range");
                            if (cr != null && cr.contains("/")) {
                                try {
                                    total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            ResponseBody body = r.body();
                            if (body == null) throw new IOException("Leere Antwort");
                            boolean whole = r.code() == 200;
                            if (whole) {
                                if (pos > 0) throw new IOException("Server unterstützt kein Fortsetzen");
                                total = body.contentLength();
                            }
                            InputStream in = body.byteStream();
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                os.write(buf, 0, n);
                                pos += n;
                                long t = total > 0 ? total : 0;
                                progress(step, part * t + pos, (long) parts * t);
                            }
                            if (whole && total < 0) total = pos;
                            break;
                        } catch (IOException e) {
                            if (++attempt >= 4) throw e;
                            try {
                                Thread.sleep(1500L * attempt);
                            } catch (InterruptedException ie) {
                                throw new IOException("Abgebrochen");
                            }
                        }
                    }
                    if (total < 0) {
                        // Größe unbekannt: kürzeres Stück als angefragt = Dateiende
                        if (pos - chunkStart < CHUNK) break;
                    }
                }
            }
            if (pos == 0) throw new IOException("Datei ist leer");
        }

        /**
         * M4A (AAC) -> PCM -> MP3 (192 kbit/s), aufgeteilt in die gewünschten Teile
         * (ganzes Lied, Kapitel oder Abschnitt). Geschnitten wird auf das Sample genau.
         */
        void toMp3(File src, List<Seg> segs, String artistName, String albumName, byte[] cover) throws IOException {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec codec = null;
            try {
                ex.setDataSource(src.getAbsolutePath());
                int track = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        track = i;
                        fmt = f;
                        break;
                    }
                }
                if (track < 0) throw new IOException("Keine Tonspur in der Datei");
                ex.selectTrack(track);
                long firstStart = segs.get(0).startUs;
                if (firstStart > 0) ex.seekTo(firstStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                        ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
                long lastEnd = segs.get(segs.size() - 1).endUs;
                if (lastEnd != Long.MAX_VALUE && lastEnd < durationUs) durationUs = lastEnd;
                int sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();

                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inputDone = false, outputDone = false;
                byte[] pcm = new byte[0];
                int outChannels = channels;
                int outRate = sampleRate;
                long[] starts = new long[segs.size()], ends = new long[segs.size()];
                for (int i = 0; i < segs.size(); i++) {
                    starts[i] = segs.get(i).startUs;
                    ends[i] = segs.get(i).endUs;
                }
                final PcmSplitter splitter = new PcmSplitter(starts, ends);
                String step = segs.size() > 1 ? "Wird in " + segs.size() + " MP3s umgewandelt" : "Wird in MP3 umgewandelt";

                while (!outputDone && !splitter.done()) {
                    if (!inputDone) {
                        int inIdx = codec.dequeueInputBuffer(10_000);
                        if (inIdx >= 0) {
                            ByteBuffer ib = inputBuffer(codec, inIdx);
                            int size = ex.readSampleData(ib, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(bi, 10_000);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    } else if (outIdx >= 0) {
                        if (bi.size > 0) {
                            ByteBuffer ob = outputBuffer(codec, outIdx);
                            ob.position(bi.offset);
                            ob.limit(bi.offset + bi.size);
                            if (pcm.length < bi.size) pcm = new byte[bi.size];
                            ob.get(pcm, 0, bi.size);
                            int len = bi.size;
                            if (outChannels > 2) len = downmix(pcm, bi.size, outChannels);
                            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) swap(pcm, len);
                            final int ch = Math.min(outChannels, 2);
                            final int frameBytes = 2 * ch;
                            final int rate = outRate;
                            final byte[] data = pcm;
                            splitter.feed(bi.presentationTimeUs, len / frameBytes, outRate, new PcmSplitter.Sink() {
                                @Override
                                public void write(int seg, int fStart, int fEnd) throws IOException {
                                    Seg sg = segs.get(seg);
                                    if (sg.enc == null) {
                                        Mp3Encoder.Tags tags = new Mp3Encoder.Tags(sg.title, artistName);
                                        tags.album = albumName;
                                        tags.track = sg.track;
                                        tags.coverJpeg = cover;
                                        sg.os = new BufferedOutputStream(new FileOutputStream(sg.file));
                                        sg.enc = new Mp3Encoder(rate, ch, rate < 32000 ? 128 : 192, tags, sg.os);
                                    }
                                    sg.enc.encode(data, fStart * frameBytes, (fEnd - fStart) * frameBytes);
                                }

                                @Override
                                public void close(int seg) throws IOException {
                                    closeSeg(segs.get(seg));
                                }
                            });
                            progress(step, bi.presentationTimeUs - Math.max(0, firstStart),
                                    Math.max(1, durationUs - Math.max(0, firstStart)));
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    }
                }
                for (Seg s : segs) closeSeg(s);
            } finally {
                for (Seg s : segs) {
                    try {
                        if (s.os != null) s.os.close();
                    } catch (IOException ignored) {
                    }
                }
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Exception ignored) {
                    }
                    codec.release();
                }
                ex.release();
            }
        }

        void closeSeg(Seg s) throws IOException {
            if (s.enc != null) {
                s.enc.finish();
                s.enc = null;
            }
            if (s.os != null) {
                s.os.close();
                s.os = null;
            }
        }

        /**
         * Bild (H.264) und Ton (AAC) zu einer MP4-Datei zusammenfügen, optional nur von–bis.
         * video und audio dürfen dieselbe Datei sein (fertige MP4 mit Ton).
         */
        void mux(File video, File audio, File out, long startUs, long endUs) throws IOException {
            MediaExtractor ve = new MediaExtractor();
            MediaExtractor ae = new MediaExtractor();
            MediaMuxer muxer = null;
            boolean started = false;
            try {
                ve.setDataSource(video.getAbsolutePath());
                ae.setDataSource(audio.getAbsolutePath());
                int vt = findTrack(ve, "video/");
                int at = findTrack(ae, "audio/");
                if (vt < 0) throw new IOException("Keine Bildspur");
                if (at < 0) throw new IOException("Keine Tonspur");
                ve.selectTrack(vt);
                ae.selectTrack(at);
                MediaFormat vf = ve.getTrackFormat(vt);
                MediaFormat af = ae.getTrackFormat(at);
                long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;

                long base = 0;
                if (startUs > 0) {
                    // Video kann nur an einem Schlüsselbild beginnen: das davor nehmen
                    ve.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    base = Math.max(0, ve.getSampleTime());
                    ae.seekTo(base, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                }
                long total = (endUs == Long.MAX_VALUE ? durUs : Math.min(endUs, durUs)) - base;

                muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int mv = muxer.addTrack(vf);
                int ma = muxer.addTrack(af);
                muxer.start();
                started = true;

                int cap = 2 * 1024 * 1024;
                if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    cap = Math.max(cap, vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                ByteBuffer buf = ByteBuffer.allocateDirect(cap);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean vDone = false, aDone = false;
                while (!vDone || !aDone) {
                    boolean takeVideo;
                    if (vDone) takeVideo = false;
                    else if (aDone) takeVideo = true;
                    else takeVideo = ve.getSampleTime() <= ae.getSampleTime();
                    MediaExtractor ex = takeVideo ? ve : ae;
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    long t = ex.getSampleTime();
                    if (size < 0 || t > endUs) {
                        if (takeVideo) vDone = true;
                        else aDone = true;
                        continue;
                    }
                    long pts = t - base;
                    if (pts < 0) {
                        ex.advance();
                        continue;
                    }
                    bi.offset = 0;
                    bi.size = size;
                    bi.presentationTimeUs = pts;
                    bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(takeVideo ? mv : ma, buf, bi);
                    ex.advance();
                    if (takeVideo) progress("Wird zusammengefügt", pts, Math.max(1, total));
                }
            } finally {
                if (muxer != null) {
                    try {
                        if (started) muxer.stop();
                    } catch (Exception ignored) {
                    }
                    muxer.release();
                }
                ve.release();
                ae.release();
            }
        }

        /** In den öffentlichen Ordner kopieren (Musik bzw. Filme, optional Unterordner). */
        Uri publish(File file, String displayName, String mime, boolean music, String sub,
                    String tTitle, String tArtist, String tAlbum) throws IOException {
            progress("Wird gespeichert …", 0, 0);
            String subPath = sub != null && !sub.isEmpty() ? "/" + Util.safeFileName(sub) : "";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (music ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/" + FOLDER + subPath);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                if (music) {
                    cv.put(MediaStore.Audio.Media.TITLE, tTitle);
                    if (tArtist != null) cv.put(MediaStore.Audio.Media.ARTIST, tArtist);
                    if (tAlbum != null) cv.put(MediaStore.Audio.Media.ALBUM, tAlbum);
                }
                Uri collection = music
                        ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = cr.insert(collection, cv);
                if (uri == null) throw new IOException("Speichern nicht möglich");
                try (InputStream in = new FileInputStream(file);
                     OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IOException("Speichern nicht möglich");
                    copy(in, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                return uri;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        music ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), FOLDER + subPath);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File target = new File(dir, displayName);
                int n = 1;
                while (target.exists()) {
                    int dot = displayName.lastIndexOf('.');
                    target = new File(dir, displayName.substring(0, dot) + " (" + (n++) + ")"
                            + displayName.substring(dot));
                }
                try (InputStream in = new FileInputStream(file);
                     OutputStream os = new FileOutputStream(target)) {
                    copy(in, os);
                }
                MediaScannerConnection.scanFile(DownloadService.this,
                        new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
                return Uri.fromFile(target);
            }
        }

        void register(String t, String a, Uri uri, String mime, long size, String thumb, String sub) {
            Downloads.Entry e = new Downloads.Entry();
            e.title = t;
            e.artist = a;
            e.uri = uri.toString();
            e.mime = mime;
            e.size = size;
            e.date = System.currentTimeMillis();
            e.videoUrl = url;
            e.thumb = thumb;
            e.folder = sub;
            Downloads.add(e);
        }

        void finished(String what, String name, Uri uri, String mime) {
            NotificationCompat.Builder b = new NotificationCompat.Builder(DownloadService.this, App.CH_DOWNLOAD)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(what)
                    .setContentText(name)
                    .setAutoCancel(true);
            Intent open;
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                open = new Intent(DownloadService.this, DownloadsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            b.setContentIntent(PendingIntent.getActivity(DownloadService.this, id, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(id, b.build());
        }
    }

    // ------------------------------------------------------------------ Hilfen

    @SuppressWarnings("deprecation")
    private static ByteBuffer inputBuffer(MediaCodec c, int i) {
        if (Build.VERSION.SDK_INT >= 21) return c.getInputBuffer(i);
        return c.getInputBuffers()[i];
    }

    @SuppressWarnings("deprecation")
    private static ByteBuffer outputBuffer(MediaCodec c, int i) {
        if (Build.VERSION.SDK_INT >= 21) return c.getOutputBuffer(i);
        return c.getOutputBuffers()[i];
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Mehrkanal (z. B. 5.1) auf Stereo: nimmt die ersten beiden Kanäle. */
    private static int downmix(byte[] pcm, int size, int channels) {
        int frames = size / (2 * channels);
        for (int f = 0; f < frames; f++) {
            int src = f * channels * 2, dst = f * 4;
            pcm[dst] = pcm[src];
            pcm[dst + 1] = pcm[src + 1];
            pcm[dst + 2] = pcm[src + 2];
            pcm[dst + 3] = pcm[src + 3];
        }
        return frames * 4;
    }

    private static void swap(byte[] pcm, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            byte t = pcm[i];
            pcm[i] = pcm[i + 1];
            pcm[i + 1] = t;
        }
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) os.write(b, 0, n);
        os.flush();
    }
}
