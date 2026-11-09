package de.delorean23.tube;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Lädt YouTube-Mediendaten so wie die NewPipe-App (POST + rn + Range).
 * Wichtig: in kleinen Stücken (je 2 MB) nacheinander, sonst drosselt YouTube
 * lange Anfragen nach den ersten Megabytes und das Video hängt.
 * Created by DeLorean23
 */
public final class YtDataSource extends BaseDataSource {

    /** Größe eines Stücks. Klein genug, dass YouTube nicht drosselt. */
    private static final long CHUNK = 2L * 1024 * 1024;

    public static final class Factory implements DataSource.Factory {
        @NonNull
        @Override
        public DataSource createDataSource() {
            return new YtDataSource();
        }
    }

    @Nullable private DataSpec spec;
    @Nullable private Response response;
    @Nullable private InputStream stream;
    private boolean opened;

    /** Nächstes Byte, das gelesen wird (Position in der Datei). */
    private long position;
    /** Letztes Byte, das ExoPlayer haben will (inklusive), -1 = bis Dateiende. */
    private long wantedEnd;
    /** Gesamtgröße der Datei, -1 = unbekannt. */
    private long total;
    /** Ende des aktuell geöffneten Stücks (inklusive). */
    private long chunkEnd;
    /** Noch zu liefernde Bytes für ExoPlayer, C.LENGTH_UNSET = unbekannt. */
    private long bytesRemaining;
    /** Schutz gegen Endlosschleife, falls YouTube leere Stücke schickt. */
    private long emptyAt = -1;

    public YtDataSource() {
        super(true);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        spec = dataSpec;
        transferInitializing(dataSpec);
        position = dataSpec.position;
        wantedEnd = dataSpec.length == C.LENGTH_UNSET ? -1 : position + dataSpec.length - 1;
        total = -1;

        openChunk(HttpDataSource.HttpDataSourceException.TYPE_OPEN);

        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else if (total > 0) {
            bytesRemaining = total - position;
        } else {
            bytesRemaining = C.LENGTH_UNSET;
        }
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    /** Öffnet das nächste Stück ab {@link #position}. */
    private void openChunk(int type) throws IOException {
        closeResponse();
        long end = position + CHUNK - 1;
        if (wantedEnd >= 0) end = Math.min(end, wantedEnd);
        if (total > 0) end = Math.min(end, total - 1);

        okhttp3.Request req = NetDownloader.get().mediaRequest(spec.uri.toString(), position, end);
        Response r = null;
        IOException last = null;
        for (int attempt = 0; attempt < 3 && r == null; attempt++) {
            try {
                r = NetDownloader.get().client().newCall(req).execute();
            } catch (IOException e) {
                last = e;
            }
        }
        if (r == null) {
            throw new HttpDataSource.HttpDataSourceException(last, spec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, type);
        }
        int code = r.code();
        if (code < 200 || code > 299) {
            Map<String, List<String>> headers = r.headers().toMultimap();
            String msg = r.message();
            r.close();
            throw new HttpDataSource.InvalidResponseCodeException(code, msg, null,
                    headers, spec, new byte[0]);
        }
        ResponseBody body = r.body();
        if (body == null) {
            r.close();
            throw new IOException("Leere Antwort");
        }
        response = r;
        stream = body.byteStream();

        // Gesamtgröße aus "Content-Range: bytes a-b/GESAMT"
        String cr = r.header("Content-Range");
        if (cr != null && cr.contains("/")) {
            try {
                total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }

        if (code == 200) {
            // Server hat Range ignoriert und schickt die ganze Datei: selbst vorspulen
            if (total < 0 && body.contentLength() > 0) total = body.contentLength();
            long skip = position;
            while (skip > 0) {
                long n = stream.skip(skip);
                if (n <= 0) {
                    if (stream.read() < 0) throw new IOException("Ende beim Vorspulen");
                    n = 1;
                }
                skip -= n;
            }
            chunkEnd = total > 0 ? total - 1 : Long.MAX_VALUE;
            if (wantedEnd >= 0) chunkEnd = Math.min(chunkEnd, wantedEnd);
        } else {
            chunkEnd = end;
            if (total > 0) chunkEnd = Math.min(chunkEnd, total - 1);
        }
    }

    private boolean atEnd() {
        if (wantedEnd >= 0 && position > wantedEnd) return true;
        return total > 0 && position >= total;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0 || atEnd()) return C.RESULT_END_OF_INPUT;

        // aktuelles Stück fertig? nahtlos das nächste holen
        if (position > chunkEnd) {
            openChunk(HttpDataSource.HttpDataSourceException.TYPE_READ);
        }

        long chunkLeft = chunkEnd - position + 1;
        int toRead = (int) Math.min(length, chunkLeft);
        if (bytesRemaining != C.LENGTH_UNSET) toRead = (int) Math.min(toRead, bytesRemaining);

        int n;
        try {
            n = stream.read(buffer, offset, toRead);
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e, spec,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_READ);
        }
        if (n == -1) {
            // Stück früher zu Ende als erwartet
            if (total < 0 || position < total) {
                if (position <= chunkEnd && total < 0 && wantedEnd < 0) {
                    // Größe unbekannt und Stück kürzer als angefragt: das war das Dateiende
                    return C.RESULT_END_OF_INPUT;
                }
                if (emptyAt == position) {
                    throw new HttpDataSource.HttpDataSourceException(
                            new IOException("YouTube liefert keine Daten mehr"), spec,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            HttpDataSource.HttpDataSourceException.TYPE_READ);
                }
                emptyAt = position;
                openChunk(HttpDataSource.HttpDataSourceException.TYPE_READ);
                return read(buffer, offset, length);
            }
            return C.RESULT_END_OF_INPUT;
        }
        position += n;
        if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return spec != null ? spec.uri : null;
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response != null ? response.headers().toMultimap() : Collections.emptyMap();
    }

    private void closeResponse() {
        if (response != null) {
            response.close();
            response = null;
        }
        stream = null;
    }

    @Override
    public void close() {
        closeResponse();
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
