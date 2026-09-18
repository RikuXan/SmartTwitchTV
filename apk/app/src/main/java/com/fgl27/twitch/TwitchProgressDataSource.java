package com.fgl27.twitch;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;

final class TwitchProgressDataSource implements DataSource {
    private final DataSource upstream;
    private final TwitchDeliveryProgress progress;
    private TwitchDeliveryProgress.Request request;
    private String outcome;

    TwitchProgressDataSource(DataSource upstream, TwitchDeliveryProgress progress) {
        this.upstream = upstream;
        this.progress = progress;
    }

    @Override public long open(DataSpec spec) throws IOException {
        String path = spec.uri.getPath();
        boolean media = path != null && (path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp4"));
        request = progress.start(media);
        outcome = "closed";
        try {
            long length = upstream.open(spec);
            progress.opened(request);
            return length;
        } catch (IOException error) {
            outcome = error.getClass().getSimpleName();
            throw error;
        }
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        progress.reading(request);
        try {
            int count = upstream.read(buffer, offset, length);
            progress.read(request, count);
            if (count < 0) outcome = "eof";
            return count;
        } catch (IOException error) {
            progress.read(request, 0);
            outcome = error.getClass().getSimpleName();
            throw error;
        }
    }

    @Override public void close() throws IOException {
        try { upstream.close(); }
        finally {
            if (request != null) {
                TwitchDiagnosticLog.i(progress.finish(request, outcome));
                request = null;
            }
        }
    }

    @Override public Uri getUri() { return upstream.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
    @Override public void addTransferListener(TransferListener listener) { upstream.addTransferListener(listener); }
}
