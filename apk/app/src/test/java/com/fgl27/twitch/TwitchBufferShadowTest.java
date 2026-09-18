package com.fgl27.twitch;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TwitchBufferShadowTest {
    private void observe(TwitchBufferShadow shadow, long start, long end, long buffer, long advance) {
        for (long time = start; time <= end; time += 100) shadow.sample(time, buffer, advance, 0, 1500000);
    }

    @Test public void waitsForOneMinuteAndKeepsSafetyMargin() {
        TwitchBufferShadow shadow = new TwitchBufferShadow();
        observe(shadow, 0, 59900, 900000, 0);
        assertTrue(shadow.snapshot().contains("shadowReady=false"));
        observe(shadow, 60000, 60000, 900000, 0);
        assertTrue(shadow.snapshot().contains("shadowHeadroomMs=750"));
        assertTrue(shadow.snapshot().contains("shadowTargetMs=750"));
    }

    @Test public void correctsHistoryForCatchup() {
        TwitchBufferShadow shadow = new TwitchBufferShadow();
        observe(shadow, 0, 60000, 900000, 0);
        shadow.sample(60100, 1000000, 200000, 0, 1500000);
        assertTrue(shadow.snapshot().contains("shadowHeadroomMs=550"));
    }

    @Test public void correctsHistoryForSlowdownAndRespectsFloor() {
        TwitchBufferShadow shadow = new TwitchBufferShadow();
        observe(shadow, 0, 60000, 900000, 0);
        shadow.sample(60100, 1100000, -200000, 800000, 1500000);
        assertTrue(shadow.snapshot().contains("shadowHeadroomMs=950"));
        assertTrue(shadow.snapshot().contains("shadowTargetMs=800"));
    }

    @Test public void retainsIsolatedDipsUntilTheyExpire() {
        TwitchBufferShadow shadow = new TwitchBufferShadow();
        shadow.sample(0, 100000, 0, 0, 1500000);
        observe(shadow, 100, 119900, 900000, 0);
        assertTrue(shadow.snapshot().contains("shadowHeadroomMs=-50"));
        shadow.sample(120000, 900000, 0, 0, 1500000);
        assertTrue(shadow.snapshot().contains("shadowHeadroomMs=750"));
    }

    @Test public void errorsDiscardHistoryAndQuarantineFiveSeconds() {
        TwitchBufferShadow shadow = new TwitchBufferShadow();
        observe(shadow, 0, 60000, 900000, 0);
        shadow.deliveryError(60000);
        observe(shadow, 60000, 124900, 900000, 0);
        assertTrue(shadow.snapshot().contains("shadowReady=false"));
        shadow.sample(125000, 900000, 0, 0, 1500000);
        assertTrue(shadow.snapshot().contains("shadowReady=true"));
    }

    @Test public void interruptionRearmsAndSourceResetClearsStalls() {
        TwitchBufferShadow shadow = new TwitchBufferShadow();
        observe(shadow, 0, 60000, 900000, 0);
        shadow.recovered(false);
        shadow.recovered(true);
        assertTrue(shadow.snapshot().contains("shadowReady=false"));
        assertTrue(shadow.snapshot().contains("shadowOrdinaryStalls=1 shadowExcludedStalls=1"));
        shadow.reset();
        assertTrue(shadow.snapshot().contains("shadowOrdinaryStalls=0 shadowExcludedStalls=0"));
    }
}
