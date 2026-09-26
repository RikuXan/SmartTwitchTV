package com.fgl27.twitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem.LiveConfiguration;
import java.util.function.LongSupplier;
import org.junit.Test;

public class TwitchLivePlaybackSpeedControlTest {

    @Test
    public void sourceCushionKeepsEarlySeedAndIsolatesLateCallbacks() {
        TwitchLivePlaybackSpeedControl control = new TwitchLivePlaybackSpeedControl(() -> 0L);
        TwitchBufferCushion oldSource = new TwitchBufferCushion();
        oldSource.seedOriginMs(500);
        control.useCushion(oldSource, null);
        assertEquals(500, control.getStallExtraMs());
        TwitchBufferCushion newSource = new TwitchBufferCushion();
        control.useCushion(newSource, null);
        oldSource.rebuffer(0);
        assertEquals(200, control.getStallExtraMs());
        newSource.seedOriginMs(700);
        assertEquals(700, control.getStallExtraMs());
        control.setLiveConfiguration(new LiveConfiguration.Builder().setTargetOffsetMs(250)
            .setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1.05f).build());
        assertEquals(700, control.getStallExtraMs());
    }

    private static final class Clock implements LongSupplier {
        long nowMs;

        @Override
        public long getAsLong() {
            return nowMs;
        }
    }

    private static final class Playback {
        final Clock clock = new Clock();
        final TwitchLivePlaybackSpeedControl control = new TwitchLivePlaybackSpeedControl(clock);
        double speedAdvanceUs;
        float speed = 1f;
        long unreadVideoUs = C.TIME_UNSET;

        Playback() {
            this(500);
        }

        Playback(long targetMs) {
            control.setLiveConfiguration(new LiveConfiguration.Builder()
                .setTargetOffsetMs(targetMs)
                .setMinPlaybackSpeed(0.97f)
                .setMaxPlaybackSpeed(1.05f)
                .build());
            control.setUnreadVideoSource(() -> unreadVideoUs);
        }

        float sample(long bufferUs) {
            clock.nowMs += 10;
            speedAdvanceUs += 10000d * (speed - 1f);
            speed = control.getAdjustedPlaybackSpeed(C.TIME_UNSET, bufferUs);
            return speed;
        }

        void hold(long durationMs, long bufferUs) {
            for (long elapsed = 0; elapsed < durationMs; elapsed += 10) sample(bufferUs);
        }

        void holdAtNormalSpeed(long durationMs, long bufferUs) {
            for (long elapsed = 0; elapsed < durationMs; elapsed += 10) sample(Math.round(bufferUs - speedAdvanceUs));
        }
    }

    @Test
    public void startupFillDoesNotEnterJitterWindow() {
        Playback p = new Playback();
        for (int i = 0; i < 400; i++) {
            assertEquals(1f, p.sample(i * 2500L), 0f);
            assertEquals(-1, p.control.getWindowedMinMs());
        }
        p.hold(2500, 1000000);
        assertTrue(p.control.getWindowedMinMs() > 900);
    }

    @Test
    public void slowingRebuildsHistoricalMinimumBeforeItsBucketExpires() {
        Playback p = new Playback();
        p.hold(14000, 500000);
        p.sample(200000);
        double advanceAtLow = p.speedAdvanceUs;
        p.hold(7000, 800000);
        long rebuiltMinimumMs = Math.round((200000 + advanceAtLow - p.speedAdvanceUs) / 1000d);
        assertTrue(rebuiltMinimumMs > 300);
        assertEquals(rebuiltMinimumMs, p.control.getWindowedMinMs(), 1);
    }

    @Test
    public void catchupConsumesHistoricalMarginBeforeItsBucketExpires() {
        Playback p = new Playback();
        p.hold(14000, 500000);
        p.control.setLiveConfiguration(new LiveConfiguration.Builder()
            .setTargetOffsetMs(100)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.05f)
            .build());
        double advanceAtLow = p.speedAdvanceUs;
        p.hold(8000, 900000);
        long remainingMinimumMs = Math.round((500000 + advanceAtLow - p.speedAdvanceUs) / 1000d);
        assertTrue(remainingMinimumMs < 400);
        assertEquals(remainingMinimumMs, p.control.getWindowedMinMs(), 1);
    }

    @Test
    public void unreadVideoWindowKeepsItsOwnMinimumUntilTheBucketExpires() {
        Playback p = new Playback();
        p.unreadVideoUs = 300000;
        p.hold(14000, 500000);
        assertEquals(300, p.control.getWindowedUnreadVideoMinMs());
        p.unreadVideoUs = 40000;
        p.sample(500000);
        p.unreadVideoUs = 300000;
        p.hold(3000, 500000);
        assertEquals(40, p.control.getWindowedUnreadVideoMinMs());
        assertEquals(500, p.control.getWindowedMinMs());
        p.hold(8000, 500000);
        assertEquals(300, p.control.getWindowedUnreadVideoMinMs());
    }

    @Test
    public void slowingRebuildsHistoricalUnreadVideoMinimum() {
        Playback p = new Playback();
        p.unreadVideoUs = 600000;
        p.hold(14000, 500000);
        p.unreadVideoUs = 50000;
        p.sample(200000);
        double advanceAtLow = p.speedAdvanceUs;
        p.unreadVideoUs = 600000;
        p.hold(7000, 800000);
        long rebuiltMinimumMs = Math.round((50000 + advanceAtLow - p.speedAdvanceUs) / 1000d);
        assertTrue(rebuiltMinimumMs > 150);
        assertEquals(rebuiltMinimumMs, p.control.getWindowedUnreadVideoMinMs(), 1);
    }

    @Test
    public void unknownUnreadVideoLeavesWindowAndLowUnavailable() {
        Playback p = new Playback();
        p.hold(14000, 500000);
        assertEquals(500, p.control.getWindowedMinMs());
        assertEquals(-1, p.control.getWindowedUnreadVideoMinMs());
        assertEquals(-1, p.control.takeUnreadVideoLowMs());
    }

    @Test
    public void unreadVideoLowReportsMinimumSinceLastTake() {
        Playback p = new Playback();
        p.unreadVideoUs = 300000;
        p.sample(500000);
        p.unreadVideoUs = 120000;
        p.sample(500000);
        p.unreadVideoUs = 250000;
        p.sample(500000);
        assertEquals(120, p.control.takeUnreadVideoLowMs());
        p.sample(500000);
        assertEquals(250, p.control.takeUnreadVideoLowMs());
        assertEquals(-1, p.control.takeUnreadVideoLowMs());
    }

    @Test
    public void measuredLowsRaiseReserveAboveTheFloor() {
        Playback p = new Playback(100);
        p.control.useCushion(new TwitchBufferCushion(0), new TwitchDeliveryProgress(p.clock));
        p.holdAtNormalSpeed(20000, 400000);
        p.holdAtNormalSpeed(10, 250000);
        p.holdAtNormalSpeed(20000, 400000);
        assertEquals(200, p.control.getStallExtraMs(), 1);
    }

    @Test
    public void stallDuringAbnormalByteGapKeepsReserve() {
        Playback p = new Playback();
        TwitchDeliveryProgress delivery = new TwitchDeliveryProgress(p.clock);
        p.control.useCushion(new TwitchBufferCushion(0), delivery);
        p.hold(14000, 500000);
        TwitchDeliveryProgress.Request request = delivery.start(true);
        delivery.read(request, 100);
        p.clock.nowMs += TwitchDeliveryProgress.ABNORMAL_BYTE_GAP_MS + 50;
        p.control.notifyRebuffer();
        p.clock.nowMs += 300;
        p.control.onPlaybackRecovered(p.clock.nowMs);
        assertTrue(p.control.wasStallExcluded());
        assertEquals(0, p.control.getStallExtraMs());
    }

    @Test
    public void stallDuringNormalDeliveryRaisesReserve() {
        Playback p = new Playback();
        TwitchDeliveryProgress delivery = new TwitchDeliveryProgress(p.clock);
        p.control.useCushion(new TwitchBufferCushion(0), delivery);
        p.hold(14000, 500000);
        TwitchDeliveryProgress.Request request = delivery.start(true);
        delivery.read(request, 100);
        p.clock.nowMs += 80;
        delivery.read(request, 100);
        p.control.notifyRebuffer();
        p.clock.nowMs += 80;
        delivery.read(request, 100);
        p.control.onPlaybackRecovered(p.clock.nowMs);
        assertFalse(p.control.wasStallExcluded());
        assertEquals(250, p.control.getStallExtraMs());
    }

    @Test
    public void errorInsideDeadbandStaysAtNormalSpeed() {
        Playback p = new Playback();
        for (int i = 0; i < 6000; i++) assertEquals(1f, p.sample(540000), 0f);
    }

    @Test
    public void deliveryRecoverySettlesAtNormalSpeed() {
        Playback p = new Playback();
        p.hold(14000, 500000);
        p.sample(250000);
        p.hold(7000, 900000);
        assertTrue(p.speed < 1f);
        for (int i = 0; i < 9000; i++) {
            long bufferUs = Math.round(500000 - p.speedAdvanceUs);
            p.sample(bufferUs);
        }
        assertEquals(1f, p.speed, 0f);
        assertEquals(500, p.control.getWindowedMinMs(), 76);
    }

    @Test
    public void speedRemainsWithinConfiguredBounds() {
        Playback p = new Playback();
        p.hold(14000, 500000);
        for (int i = 0; i < 18000; i++) {
            float speed = p.sample((i / 3000) % 2 == 0 ? 2000000 : 100000);
            assertTrue(speed >= 0.97f && speed <= 1.05f);
        }
    }

    @Test
    public void smallCorrectionIsNotRoundedToWholePercent() {
        Playback p = new Playback();
        p.hold(14000, 500000);
        p.hold(12000, 580000);
        assertTrue(p.speed > 1f && p.speed < 1.01f);
    }

    @Test
    public void catchupStopsImmediatelyWhenAvailableBufferReachesCushion() {
        Playback p = new Playback();
        p.hold(15000, 1500000);
        assertTrue(p.speed > 1f);
        assertEquals(1f, p.sample(490000), 0f);
    }

    @Test
    public void pauseGapDiscardsUnobservedSpeedCorrectionWithoutChargingStall() {
        Playback p = new Playback();
        p.hold(15000, 1500000);
        assertTrue(p.speed > 1f);
        p.clock.nowMs += 60000;
        assertEquals(1f, p.sample(1500000), 0f);
        assertEquals(-1, p.control.getWindowedMinMs());
        assertEquals(0, p.control.getStallExtraMs());
        p.hold(2500, 1500000);
        assertTrue(p.control.getWindowedMinMs() > 1400);
    }

    @Test
    public void rebufferResetsHistoryAndRetainsAdaptiveCushion() {
        Playback p = new Playback();
        p.hold(15000, 1500000);
        p.control.notifyRebuffer();
        assertEquals(250, p.control.getStallExtraMs());
        assertEquals(-1, p.control.getWindowedMinMs());
        assertEquals(1f, p.control.getAdjustedSpeed(), 0f);
        p.sample(100000);
        assertEquals(-1, p.control.getWindowedMinMs());
        p.control.notifyRebuffer();
        assertEquals(500, p.control.getStallExtraMs());
        p.control.reset();
        assertEquals(0, p.control.getStallExtraMs());
    }

    @Test
    public void deliveryFailureCancelsTemporaryBumpThroughControllerCallbacks() {
        Playback p = new Playback();
        p.hold(15000, 1000000);
        p.control.notifyRebuffer();
        p.control.onDeliveryError(p.clock.nowMs + 200);
        p.control.onPlaybackRecovered(p.clock.nowMs + 500);
        assertEquals(0, p.control.getStallExtraMs());
        assertEquals(0, p.control.getLearnedCushionMs());
        assertTrue(p.control.wasStallExcluded());
    }

    @Test
    public void disabledControlReturnsAndReportsNormalSpeed() {
        Playback p = new Playback();
        p.hold(15000, 1500000);
        p.control.setLiveConfiguration(LiveConfiguration.UNSET);
        assertEquals(1f, p.sample(1500000), 0f);
        assertEquals(1f, p.control.getAdjustedSpeed(), 0f);
    }
}
