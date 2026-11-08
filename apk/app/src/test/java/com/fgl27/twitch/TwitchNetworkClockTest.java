package com.fgl27.twitch;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import org.junit.Test;

public class TwitchNetworkClockTest {

    @Test
    public void offsetIsCurrentOnlyWithinMaximumAge() {
        long refreshedAtMs = 1000;
        long lastCurrentMs = refreshedAtMs + TwitchNetworkClock.MAX_OFFSET_AGE_MS;
        assertEquals(95000, TwitchNetworkClock.currentOffsetMs(95000, refreshedAtMs, refreshedAtMs));
        assertEquals(95000, TwitchNetworkClock.currentOffsetMs(95000, refreshedAtMs, lastCurrentMs));
        assertEquals(C.TIME_UNSET, TwitchNetworkClock.currentOffsetMs(95000, refreshedAtMs, lastCurrentMs + 1));
        assertEquals(C.TIME_UNSET, TwitchNetworkClock.currentOffsetMs(C.TIME_UNSET, C.TIME_UNSET, refreshedAtMs));
    }
}
