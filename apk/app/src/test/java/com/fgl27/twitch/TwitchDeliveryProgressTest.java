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

    @Test public void reportsOngoingAndFinishedAbnormalMediaByteGaps() {
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        TwitchDeliveryProgress.Request manifest = progress.start(false);
        TwitchDeliveryProgress.Request media = progress.start(true);
        progress.read(media, 100);
        now = TwitchDeliveryProgress.ABNORMAL_BYTE_GAP_MS;
        progress.read(media, 100);
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        now = 1000;
        assertEquals(1000, progress.lastAbnormalDeliveryMs());
        assertEquals(TwitchDeliveryProgress.ABNORMAL_BYTE_GAP_MS, progress.lastAbnormalDeliveryStartMs());
        progress.read(media, 100);
        now = 1100;
        assertEquals(1000, progress.lastAbnormalDeliveryMs());
        assertEquals(TwitchDeliveryProgress.ABNORMAL_BYTE_GAP_MS, progress.lastAbnormalDeliveryStartMs());
        progress.finish(manifest, "eof");
        now = 2000;
        progress.finish(media, "closed");
        assertEquals(2000, progress.lastAbnormalDeliveryMs());
        assertEquals(1000, progress.lastAbnormalDeliveryStartMs());
    }

    private void deliver(TwitchDeliveryProgress.Request request, long durationMs, int bytesPer10Ms) {
        for (long end = now + durationMs; now < end; ) {
            now += 10;
            progress.read(request, bytesPer10Ms);
        }
    }

    private void learnUsualRate() {
        for (int i = 0; i < 3; i++) {
            TwitchDeliveryProgress.Request request = progress.start(true);
            deliver(request, 2000, 10000);
            progress.finish(request, "eof");
        }
    }

    @Test public void trickleThatCatchesUpIsAbnormalDelivery() {
        learnUsualRate();
        TwitchDeliveryProgress.Request request = progress.start(true);
        deliver(request, 600, 10000);
        long trickleStartMs = now;
        deliver(request, 600, 2000);
        assertFalse(progress.snapshot().contains("mediaLowRateMs=-1"));
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        deliver(request, 300, 30000);
        long confirmedMs = progress.lastAbnormalDeliveryMs();
        assertTrue(confirmedMs > trickleStartMs && confirmedMs <= now);
        assertTrue(progress.lastAbnormalDeliveryStartMs() <= trickleStartMs);
        String end = progress.finish(request, "eof");
        assertFalse(end.contains(" slowMs=0"));
        assertFalse(end.contains(" lowRateMs=0"));
    }

    @Test public void quietStretchThatNeverCatchesUpStaysNormal() {
        learnUsualRate();
        TwitchDeliveryProgress.Request request = progress.start(true);
        deliver(request, 600, 10000);
        deliver(request, 800, 2000);
        deliver(request, 600, 10000);
        String end = progress.finish(request, "eof");
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        assertTrue(end.contains(" slowMs=0"));
        assertFalse(end.contains(" lowRateMs=0"));
    }

    @Test public void trickleThatFinishesLateIsAbnormalDelivery() {
        learnUsualRate();
        TwitchDeliveryProgress.Request request = progress.start(true);
        deliver(request, 600, 10000);
        deliver(request, 800, 2000);
        deliver(request, 1000, 10000);
        progress.finish(request, "eof");
        assertEquals(now, progress.lastAbnormalDeliveryMs());
    }

    @Test public void moderateSlowdownNeedsTheLongWindow() {
        learnUsualRate();
        TwitchDeliveryProgress.Request request = progress.start(true);
        deliver(request, 1200, 10000);
        deliver(request, 600, 4500);
        assertTrue(progress.snapshot().contains("mediaLowRateMs=-1"));
        deliver(request, 600, 4500);
        assertFalse(progress.snapshot().contains("mediaLowRateMs=-1"));
    }

    @Test public void pauseLimitFollowsTheStreamsTypicalPause() {
        for (int i = 0; i < 5; i++) {
            if (i == 4) assertEquals(TwitchDeliveryProgress.ABNORMAL_BYTE_GAP_MS, progress.pauseLimitMs());
            TwitchDeliveryProgress.Request request = progress.start(true);
            for (int burst = 0; burst < 12; burst++) {
                now += 160;
                progress.read(request, 160000);
            }
            progress.finish(request, "eof");
        }
        assertEquals(560, progress.pauseLimitMs());
        assertEquals(2 * 2250 + TwitchDeliveryProgress.SLOT_MS, progress.detectionDelayMs());
        TwitchDeliveryProgress.Request request = progress.start(true);
        now += 160;
        progress.read(request, 160000);
        now += 420;
        progress.read(request, 420000);
        for (int burst = 0; burst < 8; burst++) {
            now += 160;
            progress.read(request, 160000);
        }
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        assertTrue(progress.snapshot().contains("mediaLowRateMs=-1"));
        now += 600;
        assertEquals(now, progress.lastAbnormalDeliveryMs());
    }

    @Test public void normalRateVariationAndUnknownRateStayNormal() {
        TwitchDeliveryProgress.Request first = progress.start(true);
        deliver(first, 2000, 2000);
        progress.finish(first, "eof");
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        learnUsualRate();
        TwitchDeliveryProgress.Request request = progress.start(true);
        for (int i = 0; i < 10; i++) {
            deliver(request, 200, 6000);
            deliver(request, 200, 14000);
        }
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
    }

    @Test public void concurrentMediaRequestsAreNotRateChecked() {
        learnUsualRate();
        TwitchDeliveryProgress.Request first = progress.start(true);
        TwitchDeliveryProgress.Request second = progress.start(true);
        for (int i = 0; i < 150; i++) {
            now += 10;
            progress.read(first, 2000);
            progress.read(second, 2000);
        }
        assertEquals(-1, progress.lastAbnormalDeliveryMs());
        progress.finish(second, "closed");
    }
}
