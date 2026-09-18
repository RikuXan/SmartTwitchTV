package com.fgl27.twitch;

import static org.junit.Assert.*;
import org.junit.Test;

public class TwitchBufferCushionTest {
    private final TwitchBufferCushion cushion = new TwitchBufferCushion();
    private long now;

    private void advance(long duration, boolean settled) {
        for (long end = now + duration; now < end;) {
            now += 100;
            cushion.tick(now, settled);
        }
    }

    private void stall() {
        cushion.rebuffer(now);
        now += 500;
        cushion.recovered(now);
    }

    private void learn() {
        stall();
        advance(140000, false);
        assertEquals(0, cushion.getExtraUs());
        stall();
        assertEquals(250, cushion.getLearnedMs());
    }

    @Test public void failedProbeToZeroRestoresConfirmedEvidence() {
        learn();
        advance(1501000, true);
        assertEquals(0, cushion.getLearnedMs());
        assertFalse(cushion.isConfirmedByStalls());
        stall();
        assertEquals(50, cushion.getLearnedMs());
        assertTrue(cushion.isConfirmedByStalls());
    }

    @Test public void seededFirstFailureOnlyAddsTemporaryAllowance() {
        cushion.seedOriginMs(80);
        stall();
        assertFalse(cushion.isConfirmedByStalls());
        assertEquals(80, cushion.getLearnedMs());
        assertEquals(330000, cushion.getExtraUs());
        advance(140000, false);
        assertEquals(80000, cushion.getExtraUs());
        stall();
        assertTrue(cushion.isConfirmedByStalls());
        assertEquals(330, cushion.getLearnedMs());
    }

    @Test public void firstFailedSeedProbeDoesNotConfirmLearning() {
        cushion.seedOriginMs(80);
        advance(301000, true);
        stall();
        assertFalse(cushion.isConfirmedByStalls());
        assertEquals(80, cushion.getLearnedMs());
        advance(140000, false);
        assertEquals(80000, cushion.getExtraUs());
    }

    @Test public void excludedFailureDoesNotConfirmSeedAndResetClearsEvidence() {
        cushion.seedOriginMs(80);
        cushion.deliveryError(now);
        stall();
        assertFalse(cushion.isConfirmedByStalls());
        advance(140000, false);
        stall();
        assertFalse(cushion.isConfirmedByStalls());
        advance(140000, false);
        stall();
        assertTrue(cushion.isConfirmedByStalls());
        cushion.reset();
        assertFalse(cushion.isConfirmedByStalls());
    }

    @Test public void originSeedCanBeProbedAwayAndCannotBeReapplied() {
        cushion.seedOriginMs(-1);
        cushion.seedOriginMs(80);
        assertEquals(80000, cushion.getExtraUs());
        advance(301000, true);
        assertEquals(30000, cushion.getExtraUs());
        cushion.seedOriginMs(80);
        assertEquals(30000, cushion.getExtraUs());
        advance(300000, true);
        assertEquals(0, cushion.getExtraUs());
        cushion.seedOriginMs(500);
        assertEquals(0, cushion.getExtraUs());
    }

    @Test public void failedOriginProbeRestoresPreviousReserve() {
        cushion.seedOriginMs(80);
        advance(301000, true);
        stall();
        assertEquals(80, cushion.getLearnedMs());
        assertEquals(600000, cushion.getProbeIntervalMs());
    }

    @Test public void lateOriginSeedPreservesLargerLearnedReserve() {
        learn();
        cushion.seedOriginMs(80);
        assertEquals(250, cushion.getLearnedMs());
        cushion.reset();
        cushion.seedOriginMs(40);
        assertEquals(40, cushion.getLearnedMs());
    }

    @Test public void classifiesConfirmedOutagesWithoutGuessingFromRequestAge() {
        assertTrue(TwitchBufferCushion.isUnusualDeliveryError(new java.io.IOException(new java.net.SocketTimeoutException())));
        assertTrue(TwitchBufferCushion.isUnusualDeliveryError(new java.net.UnknownHostException()));
        assertFalse(TwitchBufferCushion.isUnusualDeliveryError(new java.io.EOFException()));
        assertTrue(TwitchBufferCushion.isUnusualHttpStatus(500));
        assertTrue(TwitchBufferCushion.isUnusualHttpStatus(429));
        assertTrue(TwitchBufferCushion.isUnusualHttpStatus(403));
        assertFalse(TwitchBufferCushion.isUnusualHttpStatus(404));
    }

    @Test public void repeatedFailureSurvivesAllowanceDecay() {
        learn();
        advance(180000, false);
        assertEquals(250000, cushion.getExtraUs());
    }

    @Test public void isolatedFailureDoesNotPermanentlyRaiseFloor() {
        stall();
        advance(140000, false);
        assertEquals(0, cushion.getExtraUs());
        advance(1800000, false);
        stall();
        assertEquals(0, cushion.getLearnedMs());
    }

    @Test public void errorDuringStallDoesNotTeachOrKeepBump() {
        stall();
        advance(140000, false);
        cushion.rebuffer(now);
        cushion.deliveryError(now + 200);
        cushion.recovered(now + 500);
        assertEquals(0, cushion.getExtraUs());
        assertEquals(0, cushion.getLearnedMs());
    }

    @Test public void errorBeforeStallDoesNotCountAsFirstFailure() {
        cushion.deliveryError(now);
        stall();
        advance(140000, false);
        stall();
        assertEquals(0, cushion.getLearnedMs());
    }

    @Test public void stablePlaybackProbesInSmallSteps() {
        learn();
        advance(299900, true);
        assertEquals(250, cushion.getLearnedMs());
        advance(100, true);
        assertEquals(200, cushion.getLearnedMs());
        assertEquals(200000, cushion.getExtraUs());
        assertTrue(cushion.isProbing());
        advance(300000, true);
        assertEquals(150, cushion.getLearnedMs());
    }

    @Test public void failedProbeRestoresPreviousMinimumAndBacksOff() {
        learn();
        advance(300000, true);
        stall();
        assertEquals(250, cushion.getLearnedMs());
        assertEquals(600000, cushion.getProbeIntervalMs());
        assertFalse(cushion.isProbing());
        advance(140000, false);
        assertEquals(250000, cushion.getExtraUs());
        advance(599900, true);
        assertEquals(250, cushion.getLearnedMs());
        advance(100, true);
        assertEquals(200, cushion.getLearnedMs());
    }

    @Test public void knownOutageDuringProbeDoesNotRaiseMinimumOrBackoff() {
        learn();
        advance(300000, true);
        cushion.rebuffer(now);
        cushion.deliveryError(now + 100);
        now += 500;
        cushion.recovered(now);
        assertEquals(200, cushion.getLearnedMs());
        assertEquals(200000, cushion.getExtraUs());
        assertEquals(300000, cushion.getProbeIntervalMs());
    }

    @Test public void pausesAndUnsettledPlaybackDoNotAdvanceProbe() {
        learn();
        advance(200000, true);
        now += 3600000;
        cushion.tick(now, true);
        advance(200000, true);
        advance(600000, false);
        assertEquals(250, cushion.getLearnedMs());
        advance(100000, true);
        assertEquals(200, cushion.getLearnedMs());
    }

    @Test public void newSourceResetsLearningAndProbesNeverGoBelowConfiguredFloor() {
        learn();
        advance(1800000, true);
        assertEquals(0, cushion.getExtraUs());
        assertFalse(cushion.isProbing());
        cushion.reset();
        assertEquals(0, cushion.getLearnedMs());
        stall();
        assertEquals(0, cushion.getLearnedMs());
    }

    @Test public void learnedMinimumIsCapped() {
        for (int i = 0; i < 20; i++) stall();
        assertEquals(1500, cushion.getLearnedMs());
        assertEquals(1500000, cushion.getExtraUs());
    }

    @Test public void recoveryFromOlderStallCannotFinishNewerPendingStall() {
        cushion.rebuffer(1000);
        cushion.recovered(900);
        cushion.deliveryError(1100);
        cushion.recovered(1200);
        assertEquals(0, cushion.getExtraUs());
    }
}
