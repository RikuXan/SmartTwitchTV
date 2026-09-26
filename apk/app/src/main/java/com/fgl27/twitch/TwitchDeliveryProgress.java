package com.fgl27.twitch;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class TwitchDeliveryProgress {
    static final long ABNORMAL_BYTE_GAP_MS = 250;
    private static final AtomicLong NEXT_SOURCE = new AtomicLong();
    private final long sourceId = NEXT_SOURCE.incrementAndGet();
    private final LongSupplier clock;
    private final Set<Request> active = new HashSet<>();
    private long nextRequest;
    private long lastAbnormalGapStartMs = -1;
    private long lastAbnormalGapEndMs = -1;

    TwitchDeliveryProgress(LongSupplier clock) { this.clock = clock; }

    synchronized Request start(boolean media) {
        Request request = new Request(++nextRequest, clock.getAsLong(), media);
        active.add(request);
        return request;
    }

    synchronized void opened(Request request) { request.openMs = clock.getAsLong(); }
    synchronized void reading(Request request) { request.readStartMs = clock.getAsLong(); }

    synchronized void read(Request request, int count) {
        long now = clock.getAsLong();
        if (request.readStartMs >= 0) request.maxReadWaitMs = Math.max(request.maxReadWaitMs, now - request.readStartMs);
        request.readStartMs = -1;
        if (count > 0) {
            long gapMs = noByteMs(request, now);
            request.maxByteGapMs = Math.max(request.maxByteGapMs, gapMs);
            if (request.media && gapMs > ABNORMAL_BYTE_GAP_MS) recordAbnormalGap(now - gapMs, now);
            if (request.firstByteMs < 0) request.firstByteMs = now;
            request.lastByteMs = now;
            request.bytes += count;
        }
    }

    synchronized String finish(Request request, String outcome) {
        read(request, 0);
        long now = clock.getAsLong();
        if (request.media && noByteMs(request, now) > ABNORMAL_BYTE_GAP_MS) recordAbnormalGap(now - noByteMs(request, now), now);
        active.remove(request);
        return "DELIVERY-END deliveryId=" + sourceId + " request=" + request.id + " media=" + request.media +
            " outcome=" + outcome + " durationMs=" + (clock.getAsLong() - request.startMs) +
            " openMs=" + (request.openMs < 0 ? -1 : request.openMs - request.startMs) +
            " firstByteMs=" + (request.firstByteMs < 0 ? -1 : request.firstByteMs - request.startMs) +
            " bytes=" + request.bytes + " maxByteGapMs=" + request.maxByteGapMs + " maxReadWaitMs=" + request.maxReadWaitMs;
    }

    synchronized long lastAbnormalGapMs() {
        long now = clock.getAsLong();
        for (Request request : active) {
            if (request.media && noByteMs(request, now) > ABNORMAL_BYTE_GAP_MS) return now;
        }
        return lastAbnormalGapEndMs;
    }

    synchronized long lastAbnormalGapStartMs() {
        long now = clock.getAsLong();
        long startMs = -1;
        for (Request request : active) {
            long noByteMs = noByteMs(request, now);
            if (request.media && noByteMs > ABNORMAL_BYTE_GAP_MS && (startMs < 0 || now - noByteMs < startMs)) startMs = now - noByteMs;
        }
        return startMs >= 0 ? startMs : lastAbnormalGapStartMs;
    }

    private void recordAbnormalGap(long startMs, long endMs) {
        lastAbnormalGapStartMs = startMs;
        lastAbnormalGapEndMs = endMs;
    }

    synchronized String snapshot() {
        long now = clock.getAsLong();
        int count = 0;
        long bytes = 0, mediaNoByteMs = -1, readWaitMs = -1, openWaitMs = -1;
        for (Request request : active) {
            if (!request.media) continue;
            count++;
            bytes += request.bytes;
            mediaNoByteMs = Math.max(mediaNoByteMs, noByteMs(request, now));
            if (request.readStartMs >= 0) readWaitMs = Math.max(readWaitMs, now - request.readStartMs);
            if (request.openMs < 0) openWaitMs = Math.max(openWaitMs, now - request.startMs);
        }
        return " deliveryId=" + sourceId + " mediaRequests=" + count + " mediaBytes=" + bytes +
            " mediaNoByteMs=" + mediaNoByteMs + " mediaReadWaitMs=" + readWaitMs + " mediaOpenWaitMs=" + openWaitMs;
    }

    private static long noByteMs(Request request, long now) {
        return now - (request.lastByteMs < 0 ? request.startMs : request.lastByteMs);
    }

    static final class Request {
        final long id, startMs;
        final boolean media;
        long openMs = -1, firstByteMs = -1, lastByteMs = -1, readStartMs = -1;
        long bytes, maxByteGapMs, maxReadWaitMs;
        Request(long id, long startMs, boolean media) { this.id = id; this.startMs = startMs; this.media = media; }
    }
}
