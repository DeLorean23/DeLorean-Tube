package de.delorean23.tube;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.SingleSampleMediaSource;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.schabi.newpipe.extractor.stream.VideoStream;

/**
 * Macht aus einer YouTube-URL abspielbare Quellen (getrennte Bild- und Tonspur, Live über HLS).
 * Wird vom Hintergrund-Player und von den Shorts benutzt.
 * Created by DeLorean23
 */
public final class TubeSources {

    private TubeSources() {
    }

    /** mediaId-Format: Video-URL + "\n" + gewünschte Höhe + "\n" + (a = nur Ton | v) */
    public static String mediaId(String url, int height, boolean audioOnly) {
        return url + "\n" + height + "\n" + (audioOnly ? "a" : "v");
    }

    public static String urlOf(MediaItem item) {
        return item == null ? null : item.mediaId.split("\n")[0];
    }

    /** Was gerade aufgelöst wurde. */
    public static final class Resolved {
        @Nullable public final String audioUrl;
        @Nullable public final String videoUrl;
        @Nullable public final String hlsUrl;
        public final int height;

        Resolved(@Nullable String audioUrl, @Nullable String videoUrl, @Nullable String hlsUrl, int height) {
            this.audioUrl = audioUrl;
            this.videoUrl = videoUrl;
            this.hlsUrl = hlsUrl;
            this.height = height;
        }
    }

    private static volatile int currentHeight;

    public static int currentHeight() {
        return currentHeight;
    }

    /** Nicht im UI-Thread aufrufen. */
    public static MediaItem resolve(MediaItem in) throws Exception {
        String[] parts = in.mediaId.split("\n");
        String url = parts[0];
        int height = parts.length > 1 ? Integer.parseInt(parts[1]) : 720;
        boolean audioOnly = parts.length > 2 && parts[2].equals("a");

        if (url.startsWith("local:")) return resolveLocal(in, url.substring(6));

        StreamInfo info = App.streamInfo(url);
        Resolved res;
        if (Streams.isLive(info) && info.getHlsUrl() != null && !info.getHlsUrl().isEmpty()) {
            res = new Resolved(null, null, info.getHlsUrl(), 0);
        } else {
            AudioStream a = Streams.bestAudio(info, true);
            VideoStream v = audioOnly ? null : Streams.videoFor(info, height);
            if (a == null && v == null) throw new Exception("Keine abspielbare Spur gefunden");
            String videoUrl = v != null ? v.getContent() : null;
            String audioUrl = a != null ? a.getContent() : null;
            if (v != null && !v.isVideoOnly()) audioUrl = null; // Ton ist schon drin
            res = new Resolved(audioUrl, videoUrl, null, v != null ? Streams.height(v) : 0);
        }
        currentHeight = res.height;

        MediaMetadata.Builder md = in.mediaMetadata.buildUpon()
                .setTitle(info.getName())
                .setArtist(info.getUploaderName())
                .setDisplayTitle(info.getName());
        String thumb = Util.thumb(info.getThumbnails());
        if (thumb != null) md.setArtworkUri(Uri.parse(thumb));

        String main = res.hlsUrl != null ? res.hlsUrl
                : (res.videoUrl != null ? res.videoUrl : res.audioUrl);
        MediaItem.Builder b = in.buildUpon()
                .setUri(main)
                .setMediaMetadata(md.build())
                .setTag(res);
        MediaItem.SubtitleConfiguration sub = audioOnly ? null : subtitle(info, Playback.subtitles());
        b.setSubtitleConfigurations(sub != null
                ? Collections.singletonList(sub) : Collections.emptyList());
        return b.build();
    }

    /** Heruntergeladene Datei (offline). */
    private static MediaItem resolveLocal(MediaItem in, String uri) {
        Downloads.Entry e = Downloads.find(uri);
        MediaMetadata.Builder md = in.mediaMetadata.buildUpon();
        if (e != null) {
            md.setTitle(e.title).setDisplayTitle(e.title);
            if (e.artist != null) md.setArtist(e.artist);
            if (e.thumb != null) md.setArtworkUri(Uri.parse(e.thumb));
        }
        currentHeight = 0;
        return in.buildUpon().setUri(Uri.parse(uri)).setMediaMetadata(md.build())
                .setTag(null).setSubtitleConfigurations(Collections.emptyList()).build();
    }

    // ------------------------------------------------------------ Untertitel

    /** Eine wählbare Untertitel-Sprache. */
    public static final class SubOption {
        public final String code, label;

        SubOption(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    /** Welche Untertitel gibt es für dieses Video? (erste Option = Aus) */
    public static List<SubOption> subtitleOptions(StreamInfo info) {
        List<SubOption> out = new ArrayList<>();
        out.add(new SubOption("", "Aus"));
        boolean hasGerman = false;
        List<String> seen = new ArrayList<>();
        for (SubtitlesStream s : info.getSubtitles()) {
            String code = baseCode(s.getLanguageTag()) + (s.isAutoGenerated() ? "-auto" : "");
            if (seen.contains(code)) continue;
            seen.add(code);
            if (code.equals("de")) hasGerman = true;
            String name = s.getDisplayLanguageName();
            if (name == null || name.isEmpty()) name = s.getLanguageTag();
            out.add(new SubOption(code, name + (s.isAutoGenerated() ? " (automatisch erstellt)" : "")));
        }
        if (!info.getSubtitles().isEmpty() && !hasGerman) {
            out.add(new SubOption("de-tr", "Deutsch (automatisch übersetzt)"));
        }
        return out;
    }

    private static String baseCode(String tag) {
        if (tag == null) return "";
        int i = tag.indexOf('-');
        return (i > 0 ? tag.substring(0, i) : tag).toLowerCase(java.util.Locale.ROOT);
    }

    /** Untertitel-Datei (WebVTT) für die gewünschte Sprache, oder null. */
    @Nullable
    private static MediaItem.SubtitleConfiguration subtitle(StreamInfo info, String wanted) {
        if (wanted == null || wanted.isEmpty()) return null;
        List<SubtitlesStream> subs = info.getSubtitles();
        if (subs == null || subs.isEmpty()) return null;
        SubtitlesStream pick = null;
        boolean translate = wanted.equals("de-tr");
        boolean auto = wanted.endsWith("-auto");
        String lang = translate ? "de" : (auto ? wanted.substring(0, wanted.length() - 5) : wanted);
        for (SubtitlesStream s : subs) {
            if (baseCode(s.getLanguageTag()).equals(lang) && s.isAutoGenerated() == auto) {
                pick = s;
                break;
            }
        }
        if (pick == null) {
            // gewünschte Sprache fehlt: deutsche (auch automatische) nehmen, sonst übersetzen
            for (SubtitlesStream s : subs) {
                if (baseCode(s.getLanguageTag()).equals(lang)) {
                    pick = s;
                    break;
                }
            }
        }
        String url;
        if (pick != null) {
            url = pick.getContent();
        } else {
            // Übersetzen: am besten von einer echten (nicht automatischen) Spur
            SubtitlesStream base = subs.get(0);
            for (SubtitlesStream s : subs) {
                if (!s.isAutoGenerated()) {
                    base = s;
                    break;
                }
            }
            url = base.getContent() + "&tlang=" + lang;
        }
        if (url == null) return null;
        url = url.replaceAll("&fmt=[^&]*", "") + "&fmt=vtt";
        return new MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(lang)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED)
                .build();
    }

    /** Baut aus getrennter Video- und Tonspur eine gemeinsame Quelle. */
    public static final class SourceFactory implements MediaSource.Factory {
        private final ProgressiveMediaSource.Factory progressive =
                new ProgressiveMediaSource.Factory(new YtDataSource.Factory());
        /** Dateien auf dem Gerät (Downloads) */
        private final ProgressiveMediaSource.Factory local = new ProgressiveMediaSource.Factory(
                new androidx.media3.datasource.DefaultDataSource.Factory(App.get()));
        private final HlsMediaSource.Factory hls = new HlsMediaSource.Factory(
                new DefaultHttpDataSource.Factory().setUserAgent(NetDownloader.USER_AGENT));
        private final SingleSampleMediaSource.Factory subtitles = new SingleSampleMediaSource.Factory(
                new DefaultHttpDataSource.Factory().setUserAgent(NetDownloader.USER_AGENT))
                .setTreatLoadErrorsAsEndOfStream(true);

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(
                @NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
            progressive.setDrmSessionManagerProvider(drmSessionManagerProvider);
            local.setDrmSessionManagerProvider(drmSessionManagerProvider);
            hls.setDrmSessionManagerProvider(drmSessionManagerProvider);
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(
                @NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            local.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            return this;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return new int[]{C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS};
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem item) {
            Object tag = item.localConfiguration != null ? item.localConfiguration.tag : null;
            if (!(tag instanceof Resolved)) {
                Uri u = item.localConfiguration != null ? item.localConfiguration.uri : null;
                String scheme = u != null ? u.getScheme() : null;
                if ("content".equals(scheme) || "file".equals(scheme)) return local.createMediaSource(item);
                return progressive.createMediaSource(item);
            }
            Resolved r = (Resolved) tag;
            List<MediaSource> parts = new ArrayList<>();
            if (r.hlsUrl != null) {
                parts.add(hls.createMediaSource(item.buildUpon().setUri(r.hlsUrl).build()));
            } else {
                if (r.videoUrl != null) {
                    parts.add(progressive.createMediaSource(item.buildUpon().setUri(r.videoUrl).build()));
                }
                if (r.audioUrl != null) {
                    parts.add(progressive.createMediaSource(item.buildUpon().setUri(r.audioUrl).build()));
                }
            }
            // Untertitel dazu
            if (item.localConfiguration != null) {
                for (MediaItem.SubtitleConfiguration sc : item.localConfiguration.subtitleConfigurations) {
                    parts.add(subtitles.createMediaSource(sc, C.TIME_UNSET));
                }
            }
            if (parts.size() == 1) return parts.get(0);
            return new MergingMediaSource(true, parts.toArray(new MediaSource[0]));
        }
    }
}
