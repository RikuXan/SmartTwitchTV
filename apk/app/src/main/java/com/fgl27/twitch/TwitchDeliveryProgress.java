package com.fgl27.twitch;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class TwitchDeliveryProgress {
    private static final AtomicLong NEXT_SOURCE = new AtomicLong();
    private final long sourceId = NEXT_SOURCE.incrementAndGet();
    private final LongSupplier clock;
    private final Set<Request> active = new HashSet<>();
    private long nextRequest;

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
            request.maxByteGapMs = Math.max(request.maxByteGapMs, now - (request.lastByteMs < 0 ? request.startMs : request.lastByteMs));
            if (request.firstByteMs < 0) request.firstByteMs = now;
            request.lastByteMs = now;
            request.bytes += count;
        }
    }

    synchronized String finish(Request request, String outcome) {
        read(request, 0);
        active.remove(request);
        return "DELIVERY-END deliveryId=" + sourceId + " request=" + request.id + " media=" + request.media +
            " outcome=" + outcome + " durationMs=" + (clock.getAsLong() - request.startMs) +
            " openMs=" + (request.openMs < 0 ? -1 : request.openMs - request.startMs) +
            " firstByteMs=" + (request.firstByteMs < 0 ? -1 : request.firstByteMs - request.startMs) +
            " bytes=" + request.bytes + " maxByteGapMs=" + request.maxByteGapMs + " maxReadWaitMs=" + request.maxReadWaitMs;
    }

    synchronized String snapshot() {
        long now = clock.getAsLong();
        int count = 0;
        long bytes = 0, noByteMs = -1, readWaitMs = -1, openWaitMs = -1;
        for (Request request : active) {
            if (!request.media) continue;
            count++;
            bytes += request.bytes;
            noByteMs = Math.max(noByteMs, now - (request.lastByteMs < 0 ? request.startMs : request.lastByteMs));
            if (request.readStartMs >= 0) readWaitMs = Math.max(readWaitMs, now - request.readStartMs);
            if (request.openMs < 0) openWaitMs = Math.max(openWaitMs, now - request.startMs);
        }
        return " deliveryId=" + sourceId + " mediaRequests=" + count + " mediaBytes=" + bytes +
            " mediaNoByteMs=" + noByteMs + " mediaReadWaitMs=" + readWaitMs + " mediaOpenWaitMs=" + openWaitMs;
    }

    static final class Request {
        final long id, startMs;
        final boolean media;
        long openMs = -1, firstByteMs = -1, lastByteMs = -1, readStartMs = -1;
        long bytes, maxByteGapMs, maxReadWaitMs;
        Request(long id, long startMs, boolean media) { this.id = id; this.startMs = startMs; this.media = media; }
    }
}
