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

    @Test public void needsThreeLevelBucketsBeforeRecommending() {
        hold(25000, 400000, 0);
        assertEquals(C.TIME_UNSET, lows.recommendedTargetUs());
        hold(20000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
    }

    @Test public void rareDipRaisesRecommendationByItsDepth() {
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

    @Test public void levelDriftAfterADipDoesNotDeepenIt() {
        hold(60000, 400000, 0);
        hold(10, 250000, 0);
        for (int minute = 0; minute < 10; minute++) hold(60000, 400000 + minute * 10000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US + 150000, lows.recommendedTargetUs());
    }

    @Test public void excludedDeliveryIncludingItsUndetectedOnsetDoesNotCount() {
        hold(60000, 400000, 0);
        long abnormalStartMs = now;
        hold(TwitchDeliveryProgress.DETECTION_DELAY_MS, 150000, 0);
        lows.exclude(abnormalStartMs, now + 40);
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

    @Test public void trimNeedsThreeMinutesOfMeasuredBuckets() {
        hold(200000, 400000, 0);
        assertFalse(lows.isTrimReady());
        hold(20000, 400000, 0);
        assertTrue(lows.isTrimReady());
    }

    @Test public void interruptKeepsMeasuredDipsAndRestartsTheLevel() {
        hold(60000, 400000, 0);
        hold(10, 250000, 0);
        hold(20000, 400000, 0);
        lows.interrupt();
        hold(60000, 900000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US + 150000, lows.recommendedTargetUs());
        assertFalse(lows.isTrimReady());
        assertTrue(lows.snapshot(0).contains(" lowsLevelMs=900"));
    }

    @Test public void interruptDropsSamplesNotYetCommitted() {
        hold(60000, 400000, 0);
        hold(100, 100000, 0);
        lows.interrupt();
        hold(60000, 400000, 0);
        assertEquals(TwitchBufferLows.MARGIN_US, lows.recommendedTargetUs());
    }

    @Test public void clearForgetsEverything() {
        hold(60000, 400000, 0);
        hold(10, 250000, 0);
        hold(20000, 400000, 0);
        lows.clear();
        assertEquals(C.TIME_UNSET, lows.recommendedTargetUs());
        assertTrue(lows.snapshot(0).contains(" lowsBuckets=0 "));
    }
}
