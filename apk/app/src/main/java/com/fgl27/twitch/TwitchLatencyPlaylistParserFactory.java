package com.fgl27.twitch;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory;
import androidx.media3.exoplayer.upstream.ParsingLoadable;

final class TwitchLatencyPlaylistParserFactory implements HlsPlaylistParserFactory {

    private final HlsPlaylistParserFactory delegate;
    private final TwitchBroadcastLatency latency;

    TwitchLatencyPlaylistParserFactory(boolean parsePrefetchSegments, TwitchBroadcastLatency latency) {
        delegate = new DefaultHlsPlaylistParserFactory(parsePrefetchSegments);
        this.latency = latency;
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        return observe(delegate.createPlaylistParser());
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
        HlsMultivariantPlaylist multivariantPlaylist,
        @Nullable HlsMediaPlaylist previousMediaPlaylist
    ) {
        return observe(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist));
    }

    private ParsingLoadable.Parser<HlsPlaylist> observe(ParsingLoadable.Parser<HlsPlaylist> parser) {
        return (uri, inputStream) -> {
            HlsPlaylist playlist = parser.parse(uri, inputStream);
            latency.synchronize(playlist.tags);
            return playlist;
        };
    }
}
