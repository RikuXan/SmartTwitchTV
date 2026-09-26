package com.fgl27.twitch;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import org.junit.Test;

public class TwitchBufferLowsTest {
    private final TwitchBufferLows lows = new TwitchBufferLows();
    private long now;

    private void hold(long durationMs, long bufferUs, long advanceUs) {
        for (long end = now + durationMs; now < end; now += 10) lows.sample(now, bufferUs, advanceUs);
    }

    @Test public void needsThreeCoveredBucketsBeforeRecommending() {
        hold(20000, 400000, 0);
        assertEquals(C.TIME_UNSET, lows.recommendedTargetUs());
        hold(15000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
    }

    @Test public void rareDipRaisesRecommendationByItsExtraDepth() {
        hold(60000, 400000, 0);
        hold(10, 250000, 0);
        hold(60000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US + 150000, lows.recommendedTargetUs());
    }

    @Test public void speedCorrectionAlignsLowsAcrossSpeedChanges() {
        hold(60000, 300000, 0);
        hold(60000, 400000, -100000);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
    }

    @Test public void excludedGapIncludingItsUndetectedOnsetDoesNotCount() {
        hold(60000, 400000, 0);
        long gapStartMs = now;
        hold(260, 150000, 0);
        lows.exclude(gapStartMs, now + 40);
        hold(40, 150000, 0);
        hold(60000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
    }

    @Test public void unexcludedGapWouldCountAsDip() {
        hold(60000, 400000, 0);
        hold(300, 150000, 0);
        hold(60000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US + 250000, lows.recommendedTargetUs());
    }

    @Test public void dipsExpireAfterTwentyMinutes() {
        hold(60000, 400000, 0);
        hold(10, 250000, 0);
        hold(21 * 60000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
    }

    @Test public void trimNeedsThreeMinutesOfCoveredBuckets() {
        hold(170000, 400000, 0);
        assertFalse(lows.isTrimReady());
        hold(20000, 400000, 0);
        assertTrue(lows.isTrimReady());
    }

    @Test public void interruptClearsHistoryAndPendingSamples() {
        hold(60000, 400000, 0);
        hold(100, 100000, 0);
        lows.interrupt();
        hold(60000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
        assertTrue(lows.snapshot(0).contains(" lowsWorstMs=400"));
    }
}
