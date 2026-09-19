package com.fgl27.twitch;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TwitchBroadcastLatency {

    private static final Pattern SERVER_TIME_ID = Pattern.compile("(?:[:,])DATA-ID=\"SERVER-TIME\"(?=,|$)");
    private static final Pattern SESSION_VALUE = Pattern.compile("(?:[:,])VALUE=\"([0-9]+(?:\\.[0-9]+)?)\"(?=,|$)");
    private static final Pattern TWITCH_SERVER_TIME = Pattern.compile("(?:[:,])SERVER-TIME=\"([0-9]+(?:\\.[0-9]+)?)\"(?=,|$)");

    private final LongSupplier networkClockOffsetMs;
    private long networkReferenceOffsetMs = C.TIME_UNSET;
    private final LongSupplier elapsedRealtimeMs;
    private long serverTimeMs = C.TIME_UNSET;
    private long clockReferenceMs;
    private long receiveTimeMs = C.TIME_UNSET;
    private long sendTimeMs = C.TIME_UNSET;
    private long latencyMs = C.TIME_UNSET;
    private long sampleRealtimeMs = C.TIME_UNSET;
    private long presentationTimeUs = C.TIME_UNSET;

    TwitchBroadcastLatency() {
        this(SystemClock::elapsedRealtime, TwitchNetworkClock::getOffsetMs);
    }

    TwitchBroadcastLatency(LongSupplier elapsedRealtimeMs) {
        this(elapsedRealtimeMs, () -> C.TIME_UNSET);
    }

    TwitchBroadcastLatency(LongSupplier elapsedRealtimeMs, LongSupplier networkClockOffsetMs) {
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.networkClockOffsetMs = networkClockOffsetMs;
    }

    synchronized void synchronize(List<String> tags) {
        if (serverTimeMs != C.TIME_UNSET) return;
        for (String tag : tags) {
            Matcher matcher;
            if (tag.startsWith("#EXT-X-SESSION-DATA:") && SERVER_TIME_ID.matcher(tag).find()) {
                matcher = SESSION_VALUE.matcher(tag);
            } else if (tag.startsWith("#EXT-X-TWITCH-INFO:")) {
                matcher = TWITCH_SERVER_TIME.matcher(tag);
            } else {
                continue;
            }
            if (!matcher.find()) continue;
            double timestampMs = Double.parseDouble(matcher.group(1)) * 1000;
            if (!Double.isFinite(timestampMs) || timestampMs <= 0 || timestampMs >= Long.MAX_VALUE) continue;
            long timestamp = (long) timestampMs;
            serverTimeMs = timestamp;
            clockReferenceMs = elapsedRealtimeMs.getAsLong();
            networkReferenceOffsetMs = networkClockOffsetMs.getAsLong();
            return;
        }
    }

    private long correctedTimeMs(long nowMs) {
        long offsetMs = networkClockOffsetMs.getAsLong();
        if (networkReferenceOffsetMs == C.TIME_UNSET && offsetMs != C.TIME_UNSET) {
            networkReferenceOffsetMs = offsetMs;
        }
        long driftMs = offsetMs == C.TIME_UNSET || networkReferenceOffsetMs == C.TIME_UNSET ? 0 : offsetMs - networkReferenceOffsetMs;
        return serverTimeMs + (nowMs - clockReferenceMs) + driftMs;
    }

    synchronized boolean onMetadata(Metadata metadata) {
        if (serverTimeMs == C.TIME_UNSET) return false;
        boolean updated = false;
        for (int i = 0; i < metadata.length(); i++) {
            Metadata.Entry entry = metadata.get(i);
            if (!(entry instanceof TextInformationFrame)) continue;
            TextInformationFrame frame = (TextInformationFrame) entry;
            if (!"TXXX".equals(frame.id) || !"segmentmetadata".equals(frame.description)) continue;
            for (String value : frame.values) {
                updated |= updateSample(value, metadata.presentationTimeUs);
            }
        }
        return updated;
    }

    private boolean updateSample(String value, long cuePresentationTimeUs) {
        long received;
        long sent;
        try {
            JsonObject data = JsonParser.parseString(value).getAsJsonObject();
            if (!data.has("cmd") || !"ld_lat_data".equals(data.get("cmd").getAsString())) return false;
            received = data.get("transc_r").getAsLong();
            sent = data.get("transc_s").getAsLong();
        } catch (RuntimeException malformedMetadata) {
            return false;
        }
        if (received <= 0 || sent <= 0 || received < receiveTimeMs || sent < sendTimeMs) return false;

        long nowMs = elapsedRealtimeMs.getAsLong();
        latencyMs = correctedTimeMs(nowMs) - received;
        receiveTimeMs = received;
        sendTimeMs = sent;
        sampleRealtimeMs = nowMs;
        presentationTimeUs = cuePresentationTimeUs;
        return true;
    }

    synchronized void onSeek() {
        receiveTimeMs = C.TIME_UNSET;
        sendTimeMs = C.TIME_UNSET;
        latencyMs = C.TIME_UNSET;
        sampleRealtimeMs = C.TIME_UNSET;
        presentationTimeUs = C.TIME_UNSET;
    }

    synchronized long getLatencyMs() {
        return latencyMs;
    }

    synchronized long getSampleAgeMs() {
        return sampleRealtimeMs == C.TIME_UNSET ? C.TIME_UNSET : elapsedRealtimeMs.getAsLong() - sampleRealtimeMs;
    }

    synchronized long getServerTimeMs() {
        return serverTimeMs;
    }

    synchronized String getClockDiagnostic() {
        long nowMs = elapsedRealtimeMs.getAsLong();
        return "receiveMs=" + receiveTimeMs + " sendMs=" + sendTimeMs +
            " correctedNowMs=" + correctedTimeMs(nowMs) +
            " wallNowMs=" + System.currentTimeMillis();
    }

    synchronized long getPresentationTimeUs() {
        return presentationTimeUs;
    }
}
