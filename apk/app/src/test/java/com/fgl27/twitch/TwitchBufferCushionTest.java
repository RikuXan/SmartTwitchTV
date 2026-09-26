package com.fgl27.twitch;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import org.junit.Test;

public class TwitchBufferCushionTest {
    private final TwitchBufferCushion cushion = new TwitchBufferCushion();

    private void stall(long atMs) {
        cushion.rebuffer(atMs);
        cushion.recovered(atMs + 500);
    }

    @Test public void startsWithConservativeReserveThatOriginSeedCanOnlyRaiseOnce() {
        assertEquals(200, cushion.getLearnedMs());
        cushion.seedOriginMs(80);
        assertEquals(200, cushion.getLearnedMs());
        TwitchBufferCushion farOrigin = new TwitchBufferCushion();
        farOrigin.seedOriginMs(500);
        farOrigin.seedOriginMs(900);
        assertEquals(500, farOrigin.getLearnedMs());
    }

    @Test public void normalDeliveryStallRaisesReserveAndHoldsTrims() {
        stall(0);
        assertEquals(450, cushion.getLearnedMs());
        assertFalse(cushion.wasExcluded());
        cushion.resize(200000, 100000, true);
        assertEquals(450, cushion.getLearnedMs());
        cushion.resize(1200501, 100000, true);
        assertEquals(100, cushion.getLearnedMs());
    }

    @Test public void abnormalDeliveryDuringOrShortlyBeforeStallRollsBackItsBump() {
        cushion.rebuffer(20000);
        assertEquals(450000, cushion.getExtraUs());
        cushion.deliveryError(19000);
        cushion.recovered(20500);
        assertTrue(cushion.wasExcluded());
        assertEquals(200000, cushion.getExtraUs());

        cushion.deliveryError(30000);
        stall(44000);
        assertTrue(cushion.wasExcluded());
        assertEquals(200, cushion.getLearnedMs());
    }

    @Test public void abnormalDeliveryLongBeforeStallDoesNotExcludeIt() {
        cushion.deliveryError(0);
        stall(15001);
        assertFalse(cushion.wasExcluded());
        assertEquals(450, cushion.getLearnedMs());
    }

    @Test public void raisesImmediatelyButTrimsOnlyWithEnoughObservation() {
        cushion.resize(0, 400000, false);
        assertEquals(400, cushion.getLearnedMs());
        cushion.resize(200000, 100000, false);
        assertEquals(400, cushion.getLearnedMs());
        cushion.resize(200000, 100000, true);
        assertEquals(100, cushion.getLearnedMs());
    }

    @Test public void trimsAreSpacedAndIgnoreSmallDifferences() {
        cushion.resize(0, 120000, true);
        assertEquals(120, cushion.getLearnedMs());
        cushion.resize(1000, 50000, true);
        assertEquals(120, cushion.getLearnedMs());
        cushion.resize(181000, 110000, true);
        assertEquals(120, cushion.getLearnedMs());
        cushion.resize(181000, 50000, true);
        assertEquals(50, cushion.getLearnedMs());
    }

    @Test public void pendingStallAndUnknownRecommendationDoNotResize() {
        cushion.rebuffer(0);
        cushion.resize(10, 900000, true);
        cushion.recovered(20);
        assertEquals(450, cushion.getLearnedMs());
        cushion.resize(2000000, C.TIME_UNSET, true);
        assertEquals(450, cushion.getLearnedMs());
    }

    @Test public void measuredTrimNeverGoesBelowTheFloorAndReserveIsCapped() {
        cushion.resize(0, -300000, true);
        assertEquals(0, cushion.getExtraUs());
        for (int i = 0; i < 20; i++) stall(i * 1000L);
        assertEquals(1500, cushion.getLearnedMs());
        cushion.resize(3000000, 5000000, true);
        assertEquals(1500000, cushion.getExtraUs());
    }

    @Test public void resetRestoresStartReserveAndAllowsSeedingAgain() {
        stall(0);
        cushion.seedOriginMs(100);
        cushion.reset();
        assertEquals(200, cushion.getLearnedMs());
        assertEquals(0, cushion.getTrimHoldMs(1));
        cushion.seedOriginMs(600);
        assertEquals(600, cushion.getLearnedMs());
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

    @Test public void recoveryFromOlderStallCannotFinishNewerPendingStall() {
        TwitchBufferCushion withoutStartReserve = new TwitchBufferCushion(0);
        withoutStartReserve.rebuffer(1000);
        withoutStartReserve.recovered(900);
        withoutStartReserve.deliveryError(1100);
        withoutStartReserve.recovered(1200);
        assertEquals(0, withoutStartReserve.getExtraUs());
    }
}
