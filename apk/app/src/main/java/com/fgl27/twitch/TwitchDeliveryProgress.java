package com.fgl27.twitch;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class TwitchDeliveryProgress {
    static final long ABNORMAL_BYTE_GAP_MS = 250;
    static final long SLOT_MS = 50;
    static final long SHORT_WINDOW_MS = 500;
    static final long LONG_WINDOW_MS = 1000;
    static final double SHORT_WINDOW_MIN_RATE = 0.3;
    static final double LONG_WINDOW_MIN_RATE = 0.5;
    static final long DETECTION_DELAY_MS = LONG_WINDOW_MS + SLOT_MS;
    static final long MIN_RATE_REQUEST_MS = 1000;
    private static final int SLOTS = (int) (LONG_WINDOW_MS / SLOT_MS);
    private static final int RATE_REQUESTS = 5;
    private static final int MIN_RATE_REQUESTS = 3;
    private static final AtomicLong NEXT_SOURCE = new AtomicLong();
    private final long sourceId = NEXT_SOURCE.incrementAndGet();
    private final LongSupplier clock;
    private final Set<Request> active = new HashSet<>();
    private final double[] requestRates = new double[RATE_REQUESTS];
    private int requestRateCount;
    private int requestRateNext;
    private long nextRequest;
    private long lastAbnormalStartMs = -1;
    private long lastAbnormalEndMs = -1;

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
            if (request.media && gapMs > ABNORMAL_BYTE_GAP_MS) recordAbnormal(now - gapMs, now);
            if (request.firstByteMs < 0) request.firstByteMs = now;
            request.lastByteMs = now;
            request.bytes += count;
            if (request.media) trackRate(request, now, count);
        }
    }

    synchronized String finish(Request request, String outcome) {
        read(request, 0);
        long now = clock.getAsLong();
        if (request.media && noByteMs(request, now) > ABNORMAL_BYTE_GAP_MS) recordAbnormal(now - noByteMs(request, now), now);
        endSlow(request, now);
        active.remove(request);
        long durationMs = now - request.startMs;
        if (request.media && "eof".equals(outcome) && durationMs >= MIN_RATE_REQUEST_MS) {
            requestRates[requestRateNext] = (double) request.bytes / durationMs;
            requestRateNext = (requestRateNext + 1) % RATE_REQUESTS;
            requestRateCount = Math.min(requestRateCount + 1, RATE_REQUESTS);
        }
        return "DELIVERY-END deliveryId=" + sourceId + " request=" + request.id + " media=" + request.media +
            " outcome=" + outcome + " durationMs=" + durationMs +
            " openMs=" + (request.openMs < 0 ? -1 : request.openMs - request.startMs) +
            " firstByteMs=" + (request.firstByteMs < 0 ? -1 : request.firstByteMs - request.startMs) +
            " bytes=" + request.bytes + " maxByteGapMs=" + request.maxByteGapMs + " maxReadWaitMs=" + request.maxReadWaitMs +
            " slowMs=" + request.slowMs;
    }

    synchronized long lastAbnormalDeliveryMs() {
        long now = clock.getAsLong();
        for (Request request : active) {
            if (request.media && (noByteMs(request, now) > ABNORMAL_BYTE_GAP_MS || request.slowFromMs >= 0)) return now;
        }
        return lastAbnormalEndMs;
    }

    synchronized long lastAbnormalDeliveryStartMs() {
        long now = clock.getAsLong();
        long startMs = -1;
        for (Request request : active) {
            if (!request.media) continue;
            long noByteMs = noByteMs(request, now);
            if (noByteMs > ABNORMAL_BYTE_GAP_MS) startMs = earliest(startMs, now - noByteMs);
            if (request.slowFromMs >= 0) startMs = earliest(startMs, request.slowFromMs);
        }
        return startMs >= 0 ? startMs : lastAbnormalStartMs;
    }

    private static long earliest(long currentMs, long candidateMs) {
        return currentMs < 0 ? candidateMs : Math.min(currentMs, candidateMs);
    }

    private void recordAbnormal(long startMs, long endMs) {
        lastAbnormalStartMs = startMs;
        lastAbnormalEndMs = endMs;
    }

    private void trackRate(Request request, long now, int count) {
        long slot = now / SLOT_MS;
        if (slot != request.slot) {
            long elapsed = request.slot < 0 ? SLOTS : Math.min(slot - request.slot, SLOTS);
            for (long i = 0; i < elapsed; i++) request.slotBytes[(int) Math.floorMod(slot - i, SLOTS)] = 0;
            request.slot = slot;
            evaluateRate(request, slot * SLOT_MS);
        }
        request.slotBytes[(int) Math.floorMod(slot, SLOTS)] += count;
    }

    private void evaluateRate(Request request, long windowEndMs) {
        double usualBytesPerMs = usualBytesPerMs();
        if (usualBytesPerMs <= 0 || activeMediaRequests() != 1) {
            endSlow(request, windowEndMs);
            return;
        }
        long slowFromMs = isBelow(request, windowEndMs, LONG_WINDOW_MS, LONG_WINDOW_MIN_RATE, usualBytesPerMs)
            ? windowEndMs - LONG_WINDOW_MS
            : isBelow(request, windowEndMs, SHORT_WINDOW_MS, SHORT_WINDOW_MIN_RATE, usualBytesPerMs) ? windowEndMs - SHORT_WINDOW_MS : -1;
        if (slowFromMs < 0) {
            endSlow(request, windowEndMs);
        } else if (request.slowFromMs < 0) {
            request.slowFromMs = slowFromMs;
        }
    }

    private static boolean isBelow(Request request, long windowEndMs, long windowMs, double minRate, double usualBytesPerMs) {
        if (windowEndMs - windowMs < request.startMs) return false;
        long bytes = 0;
        long lastSlot = windowEndMs / SLOT_MS - 1;
        for (long i = 0; i < windowMs / SLOT_MS; i++) bytes += request.slotBytes[(int) Math.floorMod(lastSlot - i, SLOTS)];
        return bytes < minRate * usualBytesPerMs * windowMs;
    }

    private void endSlow(Request request, long endMs) {
        if (request.slowFromMs < 0) return;
        recordAbnormal(request.slowFromMs, endMs);
        request.slowMs += endMs - request.slowFromMs;
        request.slowFromMs = -1;
    }

    private double usualBytesPerMs() {
        if (requestRateCount < MIN_RATE_REQUESTS) return -1;
        double[] sorted = Arrays.copyOf(requestRates, requestRateCount);
        Arrays.sort(sorted);
        return sorted[requestRateCount / 2];
    }

    private int activeMediaRequests() {
        int count = 0;
        for (Request request : active) {
            if (request.media) count++;
        }
        return count;
    }

    synchronized String snapshot() {
        long now = clock.getAsLong();
        int count = 0;
        long bytes = 0, mediaNoByteMs = -1, readWaitMs = -1, openWaitMs = -1, slowMs = -1;
        for (Request request : active) {
            if (!request.media) continue;
            count++;
            bytes += request.bytes;
            mediaNoByteMs = Math.max(mediaNoByteMs, noByteMs(request, now));
            if (request.readStartMs >= 0) readWaitMs = Math.max(readWaitMs, now - request.readStartMs);
            if (request.openMs < 0) openWaitMs = Math.max(openWaitMs, now - request.startMs);
            if (request.slowFromMs >= 0) slowMs = Math.max(slowMs, now - request.slowFromMs);
        }
        return " deliveryId=" + sourceId + " mediaRequests=" + count + " mediaBytes=" + bytes +
            " mediaNoByteMs=" + mediaNoByteMs + " mediaReadWaitMs=" + readWaitMs + " mediaOpenWaitMs=" + openWaitMs +
            " mediaSlowMs=" + slowMs;
    }

    private static long noByteMs(Request request, long now) {
        return now - (request.lastByteMs < 0 ? request.startMs : request.lastByteMs);
    }

    static final class Request {
        final long id, startMs;
        final boolean media;
        final long[] slotBytes = new long[SLOTS];
        long openMs = -1, firstByteMs = -1, lastByteMs = -1, readStartMs = -1, slot = -1, slowFromMs = -1;
        long bytes, maxByteGapMs, maxReadWaitMs, slowMs;
        Request(long id, long startMs, boolean media) { this.id = id; this.startMs = startMs; this.media = media; }
    }
}
