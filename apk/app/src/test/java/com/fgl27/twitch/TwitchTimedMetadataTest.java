package com.fgl27.twitch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.metadata.emsg.EventMessage;
import androidx.media3.extractor.metadata.id3.Id3Decoder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;

public class TwitchTimedMetadataTest {

    @Test
    public void media3RecognizesTwitchId3Events() {
        byte[] payload = new byte[] {73, 68, 51};
        EventMessage event = new EventMessage("urn:twitch:id3", "", 0, 0, payload);
        assertNotNull(event.getWrappedMetadataFormat());
        assertEquals(MimeTypes.APPLICATION_ID3, event.getWrappedMetadataFormat().sampleMimeType);
        assertArrayEquals(payload, event.getWrappedMetadataBytes());
    }

    @Test
    public void capturedTwitchMetadataProducesLatencyAtItsPlaybackCue() throws Exception {
        byte[] id3 = Files.readAllBytes(Paths.get(getClass().getResource("/twitch/segmentmetadata.id3").toURI()));
        EventMessage event = new EventMessage("urn:twitch:id3", "", 0, 0, id3);
        byte[] wrapped = event.getWrappedMetadataBytes();
        assertNotNull(wrapped);
        Metadata metadata = new Id3Decoder().decode(wrapped, wrapped.length);
        assertNotNull(metadata);
        TwitchBroadcastLatency latency = new TwitchBroadcastLatency(() -> 5000L);
        latency.synchronize(Collections.singletonList("#EXT-X-TWITCH-INFO:SERVER-TIME=\"1788856346.177\""));
        assertTrue(latency.onMetadata(metadata.copyWithPresentationTimeUs(12000000)));
        assertEquals(1500, latency.getLatencyMs());
        assertEquals(12000000, latency.getPresentationTimeUs());
    }
}
