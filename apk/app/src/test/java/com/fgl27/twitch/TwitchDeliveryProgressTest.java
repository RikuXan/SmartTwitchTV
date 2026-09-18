package com.fgl27.twitch;

import static org.junit.Assert.*;
import org.junit.Test;

public class TwitchDeliveryProgressTest {
    private long now;
    private final TwitchDeliveryProgress progress = new TwitchDeliveryProgress(() -> now);

    @Test public void distinguishesResponseWaitFromBlockedReadAndConsumerGap() {
        TwitchDeliveryProgress.Request request = progress.start(true);
        now = 100;
        assertTrue(progress.snapshot().contains("mediaOpenWaitMs=100"));
        progress.opened(request);
        progress.reading(request);
        now = 400;
        assertTrue(progress.snapshot().contains("mediaReadWaitMs=300"));
        progress.read(request, 1024);
        now = 900;
        assertTrue(progress.snapshot().contains("mediaReadWaitMs=-1"));
        assertTrue(progress.snapshot().contains("mediaNoByteMs=500"));
        String end = progress.finish(request, "eof");
        assertTrue(end.contains("firstByteMs=400"));
        assertTrue(end.contains("maxReadWaitMs=300"));
        assertTrue(end.contains("bytes=1024"));
        assertTrue(progress.snapshot().contains("mediaRequests=0"));
    }

    @Test public void excludesManifestBytesAndTracksConcurrentMediaRequests() {
        TwitchDeliveryProgress.Request manifest = progress.start(false);
        progress.read(manifest, 9999);
        TwitchDeliveryProgress.Request first = progress.start(true);
        TwitchDeliveryProgress.Request second = progress.start(true);
        progress.read(first, 100);
        progress.read(second, 200);
        assertTrue(progress.snapshot().contains("mediaBytes=300"));
        assertTrue(progress.snapshot().contains("mediaRequests=2"));
        progress.finish(first, "eof");
        assertTrue(progress.snapshot().contains("mediaBytes=200"));
    }

    @Test public void failedOpenAndReadWaitAreRetainedOnFinish() {
        TwitchDeliveryProgress.Request request = progress.start(true);
        now = 200;
        String failed = progress.finish(request, "IOException");
        assertTrue(failed.contains("openMs=-1"));
        assertTrue(failed.contains("firstByteMs=-1"));
        assertTrue(failed.contains("durationMs=200"));
        request = progress.start(true);
        progress.opened(request);
        progress.reading(request);
        now = 1200;
        String ended = progress.finish(request, "closed");
        assertTrue(ended.contains("maxReadWaitMs=1000"));
        assertTrue(progress.snapshot().contains("mediaRequests=0"));
    }
}
