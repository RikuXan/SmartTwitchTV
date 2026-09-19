package com.fgl27.twitch;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TwitchRecoveryPolicyTest {
    @Test public void usesCushionWithMarginAndMinimum() {
        TwitchRecoveryPolicy p = new TwitchRecoveryPolicy();
        p.stall(1000);
        assertEquals(500, p.thresholdMs(1000, 200, 1100));
        assertEquals(750, p.thresholdMs(1000, 600, 1100));
    }
    @Test public void neverExceedsExistingThreshold() {
        assertEquals(1100, new TwitchRecoveryPolicy().thresholdMs(0, 1800, 1100));
    }
    @Test public void inactiveModeRetainsExistingThreshold() {
        assertEquals(1100, new TwitchRecoveryPolicy().thresholdMs(0, -1, 1100));
    }
    @Test public void repeatedStallFallsBackForOneMinute() {
        TwitchRecoveryPolicy p = new TwitchRecoveryPolicy();
        p.stall(0);
        p.stall(30000);
        assertEquals(1100, p.thresholdMs(89999, 200, 1100));
        assertEquals(500, p.thresholdMs(90000, 200, 1100));
    }
    @Test public void WidelySeparatedStallsDoNotTriggerFallback() {
        TwitchRecoveryPolicy p = new TwitchRecoveryPolicy();
        p.stall(0);
        p.stall(30001);
        assertEquals(500, p.thresholdMs(30001, 200, 1100));
    }
    @Test public void errorsExtendFallback() {
        TwitchRecoveryPolicy p = new TwitchRecoveryPolicy();
        p.deliveryError(1000);
        p.deliveryError(10000);
        assertEquals(1100, p.thresholdMs(69999, 200, 1100));
        assertEquals(500, p.thresholdMs(70000, 200, 1100));
    }
    @Test public void sourceResetClearsFallbackAndFailureHistory() {
        TwitchRecoveryPolicy p = new TwitchRecoveryPolicy();
        p.stall(0);
        p.deliveryError(1);
        p.reset();
        p.stall(2);
        assertEquals(500, p.thresholdMs(2, 200, 1100));
    }
}
