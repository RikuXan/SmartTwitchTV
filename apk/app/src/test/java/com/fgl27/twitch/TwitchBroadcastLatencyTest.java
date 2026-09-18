package com.fgl27.twitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class TwitchBroadcastLatencyTest {

    private final AtomicLong clock = new AtomicLong(5000);
    private final TwitchBroadcastLatency latency = new TwitchBroadcastLatency(clock::get);

    @Test
    public void requiresServerReferenceAndPlaybackCue() {
        assertFalse(latency.onMetadata(cue(99500, 99900)));
        assertEquals(C.TIME_UNSET, latency.getLatencyMs());
        synchronize("100");
        assertEquals(C.TIME_UNSET, latency.getLatencyMs());
        clock.addAndGet(1000);
        assertTrue(latency.onMetadata(cue(99500, 99900)));
        assertEquals(1500, latency.getLatencyMs());
    }

    @Test
    public void usesTranscoderReceiveInsteadOfSendOrIngest() {
        synchronize("100");
        clock.addAndGet(1000);
        assertTrue(latency.onMetadata(cue(99500, 99900)));
        assertEquals(1500, latency.getLatencyMs());
        assertEquals(12000000, latency.getPresentationTimeUs());
        assertEquals(0, latency.getSampleAgeMs());
    }

    @Test
    public void retainsSampleBetweenCuesIncludingWhilePausedOrStalled() {
        synchronize("100");
        clock.addAndGet(1000);
        latency.onMetadata(cue(99500, 99900));
        clock.addAndGet(8000);
        assertEquals(1500, latency.getLatencyMs());
        assertEquals(8000, latency.getSampleAgeMs());
    }

    @Test
    public void acceptsEqualTimestampPairAsBrowserDoes() {
        synchronize("100");
        clock.addAndGet(1000);
        latency.onMetadata(cue(99500, 99900));
        clock.addAndGet(200);
        assertTrue(latency.onMetadata(cue(99500, 99900)));
        assertEquals(1700, latency.getLatencyMs());
    }

    @Test
    public void rejectsEitherTimestampGoingBackwardsWithoutChangingCachedSample() {
        synchronize("100");
        clock.addAndGet(1000);
        latency.onMetadata(cue(99500, 99900));
        clock.addAndGet(200);
        assertFalse(latency.onMetadata(cue(99499, 100000)));
        assertFalse(latency.onMetadata(cue(99600, 99899)));
        assertEquals(1500, latency.getLatencyMs());
        assertEquals(200, latency.getSampleAgeMs());
        assertTrue(latency.onMetadata(cue(99600, 100000)));
        assertEquals(1600, latency.getLatencyMs());
    }

    @Test
    public void parsesCapturedSessionDataWithFractionalSeconds() {
        synchronize("1787864805.41");
        assertEquals(1787864805410L, latency.getServerTimeMs());
        clock.addAndGet(1000);
        latency.onMetadata(cue(1787864804910L, 1787864805310L));
        assertEquals(1500, latency.getLatencyMs());
    }

    @Test
    public void parsesLegacyTwitchInfoReturnedForAppRequests() {
        latency.synchronize(Collections.singletonList(
            "#EXT-X-TWITCH-INFO:NODE=\"test\",SUPPRESS=\"true\",SERVER-TIME=\"1788856291.36\",ORIGIN=\"euw13\""
        ));
        assertEquals(1788856291360L, latency.getServerTimeMs());
        clock.addAndGet(1000);
        latency.onMetadata(cue(1788856290860L, 1788856291260L));
        assertEquals(1500, latency.getLatencyMs());
    }

    @Test
    public void ignoresUnrelatedOrInvalidSessionDataAndAllowsLaterSynchronization() {
        for (String value : Arrays.asList("NaN", "Infinity", "-1", "0", "garbage")) synchronize(value);
        latency.synchronize(Arrays.asList(
            "#EXT-X-SESSION-DATA:DATA-ID=\"OTHER\",VALUE=\"100\"",
            "#EXT-X-SESSION-DATA:DATA-ID=\"SERVER-TIME\",URI=\"https://example.invalid\""
        ));
        assertEquals(C.TIME_UNSET, latency.getServerTimeMs());
        latency.synchronize(Collections.singletonList("#EXT-X-SESSION-DATA:VALUE=\"100\",DATA-ID=\"SERVER-TIME\""));
        assertEquals(100000, latency.getServerTimeMs());
    }

    @Test
    public void doesNotReuseMasterClockOnPlaylistReload() {
        synchronize("100");
        clock.addAndGet(1000);
        synchronize("200");
        latency.onMetadata(cue(99500, 99900));
        assertEquals(1500, latency.getLatencyMs());
        assertEquals(100000, latency.getServerTimeMs());
    }

    @Test
    public void localClockOriginDoesNotAffectLatency() {
        clock.set(90000000);
        synchronize("100");
        clock.addAndGet(1000);
        latency.onMetadata(cue(99500, 99900));
        assertEquals(1500, latency.getLatencyMs());
    }

    @Test
    public void malformedAndUnrelatedMetadataCannotReplaceSample() {
        synchronize("100");
        latency.onMetadata(cue(99500, 99900));
        for (String value : Arrays.asList(
            "invalid", "null", "[]", "{}", "{\"cmd\":null}",
            "{\"cmd\":\"ld_lat_data\",\"transc_r\":99500}",
            "{\"cmd\":\"ld_lat_data\",\"transc_r\":\"bad\",\"transc_s\":99900}",
            "{\"cmd\":\"other\",\"transc_r\":99500,\"transc_s\":99900}"
        )) assertFalse(latency.onMetadata(metadata("TXXX", "segmentmetadata", value)));
        assertFalse(latency.onMetadata(metadata("TXXX", "other", json(99500, 99900))));
        assertFalse(latency.onMetadata(metadata("TIT2", "segmentmetadata", json(99500, 99900))));
        assertFalse(latency.onMetadata(cue(0, 99900)));
        assertFalse(latency.onMetadata(cue(99500, -1)));
        assertEquals(500, latency.getLatencyMs());
    }

    @Test
    public void seekInvalidatesSampleButRetainsClockAndAllowsOlderCues() {
        synchronize("100");
        latency.onMetadata(cue(99500, 99900));
        latency.onSeek();
        assertEquals(C.TIME_UNSET, latency.getLatencyMs());
        assertEquals(C.TIME_UNSET, latency.getSampleAgeMs());
        assertEquals(C.TIME_UNSET, latency.getPresentationTimeUs());
        assertTrue(latency.onMetadata(cue(98500, 98900)));
        assertEquals(1500, latency.getLatencyMs());
    }

    @Test
    public void newSourceDoesNotInheritPreviousClockOrSample() {
        synchronize("100");
        latency.onMetadata(cue(99500, 99900));
        TwitchBroadcastLatency otherSource = new TwitchBroadcastLatency(clock::get);
        assertEquals(C.TIME_UNSET, otherSource.getLatencyMs());
        assertFalse(otherSource.onMetadata(cue(99500, 99900)));
        otherSource.synchronize(Collections.singletonList("#EXT-X-SESSION-DATA:DATA-ID=\"SERVER-TIME\",VALUE=\"200\""));
        otherSource.onMetadata(cue(198000, 198500));
        assertEquals(2000, otherSource.getLatencyMs());
        assertEquals(500, latency.getLatencyMs());
    }

    @Test
    public void tracksSlowDeviceClockAcrossFiveHours() {
        assertClockDrift(1 - 1.0 / 24000);
    }

    @Test
    public void tracksFastDeviceClockAcrossFiveHours() {
        assertClockDrift(1 + 1.0 / 24000);
    }

    private void assertClockDrift(double rate) {
        AtomicLong networkOffset = new AtomicLong(95000);
        TwitchBroadcastLatency tracked = new TwitchBroadcastLatency(clock::get, networkOffset::get);
        tracked.synchronize(Collections.singletonList("#EXT-X-TWITCH-INFO:SERVER-TIME=\"100\""));
        for (int seconds = 1; seconds <= 18000; seconds++) {
            long serverNowMs = 100000 + seconds * 1000L;
            clock.set(5000 + Math.round(seconds * 1000 * rate));
            if (seconds % 30 == 0) networkOffset.set(serverNowMs - clock.get());
            tracked.onMetadata(cue(serverNowMs - 720, serverNowMs - 650));
            assertTrue("latency=" + tracked.getLatencyMs(), Math.abs(tracked.getLatencyMs() - 720) <= 2);
        }
    }

    @Test
    public void preservesTwitchClockOffsetFromNetworkTime() {
        AtomicLong networkOffset = new AtomicLong(95050);
        TwitchBroadcastLatency tracked = new TwitchBroadcastLatency(clock::get, networkOffset::get);
        tracked.synchronize(Collections.singletonList("#EXT-X-TWITCH-INFO:SERVER-TIME=\"100\""));
        clock.addAndGet(1000);
        tracked.onMetadata(cue(100500, 100900));
        assertEquals(500, tracked.getLatencyMs());
        networkOffset.addAndGet(100);
        tracked.onMetadata(cue(100500, 100900));
        assertEquals(600, tracked.getLatencyMs());
    }

    @Test
    public void lateNetworkInitializationDoesNotJumpLatency() {
        AtomicLong networkOffset = new AtomicLong(C.TIME_UNSET);
        TwitchBroadcastLatency tracked = new TwitchBroadcastLatency(clock::get, networkOffset::get);
        tracked.synchronize(Collections.singletonList("#EXT-X-TWITCH-INFO:SERVER-TIME=\"100\""));
        clock.addAndGet(1000);
        tracked.onMetadata(cue(100500, 100900));
        networkOffset.set(123456);
        tracked.onMetadata(cue(100500, 100900));
        assertEquals(500, tracked.getLatencyMs());
        networkOffset.addAndGet(100);
        tracked.onMetadata(cue(100500, 100900));
        assertEquals(600, tracked.getLatencyMs());
    }

    private void synchronize(String serverTime) {
        latency.synchronize(Collections.singletonList("#EXT-X-SESSION-DATA:DATA-ID=\"SERVER-TIME\",VALUE=\"" + serverTime + "\""));
    }

    private static Metadata cue(long received, long sent) {
        return metadata("TXXX", "segmentmetadata", json(received, sent));
    }

    private static String json(long received, long sent) {
        return "{\"cmd\":\"ld_lat_data\",\"transc_r\":" + received + ",\"transc_s\":" + sent + ",\"ingest_r\":99000}";
    }

    private static Metadata metadata(String id, String description, String value) {
        return new Metadata(12000000, new TextInformationFrame(id, description, Collections.singletonList(value)));
    }
}
