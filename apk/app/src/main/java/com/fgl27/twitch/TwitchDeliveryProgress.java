package com.fgl27.twitch;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class TwitchDeliveryProgress {
    static final long ABNORMAL_BYTE_GAP_MS = 250;
    static final double PAUSE_LIMIT_FACTOR = 3.5;
    static final long SLOT_MS = 50;
    static final long SHORT_WINDOW_MS = 500;
    static final long LONG_WINDOW_MS = 1000;
    static final double SHORT_WINDOW_MIN_RATE = 0.3;
    static final double LONG_WINDOW_MIN_RATE = 0.5;
    static final double CATCH_UP_RATE = 0.9;
    static final double MAX_WINDOW_SCALE = 3;
    static final long BASE_DETECTION_DELAY_MS = 2 * LONG_WINDOW_MS + SLOT_MS;
    static final long MIN_RATE_REQUEST_MS = 1000;
    private static final int SLOTS = (int) Math.ceil(LONG_WINDOW_MS * MAX_WINDOW_SCALE / SLOT_MS);
    private static final int RATE_REQUESTS = 5;
    private static final int MIN_RATE_REQUESTS = 3;
    private static final int PAUSE_REQUESTS = 20;
    private static final int MIN_PAUSE_REQUESTS = 5;
    private static final AtomicLong NEXT_SOURCE = new AtomicLong();
    private final long sourceId = NEXT_SOURCE.incrementAndGet();
    private final LongSupplier clock;
    private final Set<Request> active = new HashSet<>();
    private final double[] requestRates = new double[RATE_REQUESTS];
    private int requestRateCount;
    private int requestRateNext;
    private final long[] requestPausesMs = new long[PAUSE_REQUESTS];
    private final long[] requestDurationsMs = new long[PAUSE_REQUESTS];
    private int requestPauseCount;
    private int requestPauseNext;
    private volatile long pauseLimitMs = ABNORMAL_BYTE_GAP_MS;
    private long typicalDurationMs = -1;
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
            if (request.media && gapMs > pauseLimitMs) recordAbnormal(now - gapMs, now);
            if (request.firstByteMs < 0) request.firstByteMs = now;
            request.lastByteMs = now;
            request.bytes += count;
            if (request.media) trackRate(request, now, count);
        }
    }

    synchronized String finish(Request request, String outcome) {
        read(request, 0);
        long now = clock.getAsLong();
        long durationMs = now - request.startMs;
        if (request.media && noByteMs(request, now) > pauseLimitMs) recordAbnormal(now - noByteMs(request, now), now);
        endLowRate(request, now);
        if (request.pendingFromMs >= 0 && typicalDurationMs > 0 && durationMs > typicalDurationMs + pauseLimitMs) confirmSlow(request, now);
        active.remove(request);
        if (request.media && "eof".equals(outcome) && durationMs >= MIN_RATE_REQUEST_MS) learn(request, durationMs);
        return "DELIVERY-END deliveryId=" + sourceId + " request=" + request.id + " media=" + request.media +
            " outcome=" + outcome + " durationMs=" + durationMs +
            " openMs=" + (request.openMs < 0 ? -1 : request.openMs - request.startMs) +
            " firstByteMs=" + (request.firstByteMs < 0 ? -1 : request.firstByteMs - request.startMs) +
            " bytes=" + request.bytes + " maxByteGapMs=" + request.maxByteGapMs + " maxReadWaitMs=" + request.maxReadWaitMs +
            " lowRateMs=" + request.lowRateMs + " slowMs=" + request.slowMs;
    }

    synchronized long lastAbnormalDeliveryMs() {
        long now = clock.getAsLong();
        for (Request request : active) {
            if (request.media && noByteMs(request, now) > pauseLimitMs) return now;
        }
        return lastAbnormalEndMs;
    }

    synchronized long lastAbnormalDeliveryStartMs() {
        long now = clock.getAsLong();
        long startMs = -1;
        for (Request request : active) {
            long noByteMs = noByteMs(request, now);
            if (request.media && noByteMs > pauseLimitMs && (startMs < 0 || now - noByteMs < startMs)) startMs = now - noByteMs;
        }
        return startMs >= 0 ? startMs : lastAbnormalStartMs;
    }

    long pauseLimitMs() { return pauseLimitMs; }

    long detectionDelayMs() { return 2 * windowMs(LONG_WINDOW_MS) + SLOT_MS; }

    private long windowMs(long baseMs) {
        double scale = Math.min(MAX_WINDOW_SCALE, (double) pauseLimitMs / ABNORMAL_BYTE_GAP_MS);
        return Math.max(SLOT_MS, Math.round(baseMs * scale / SLOT_MS) * SLOT_MS);
    }

    private void learn(Request request, long durationMs) {
        requestRates[requestRateNext] = (double) request.bytes / durationMs;
        requestRateNext = (requestRateNext + 1) % RATE_REQUESTS;
        requestRateCount = Math.min(requestRateCount + 1, RATE_REQUESTS);
        requestPausesMs[requestPauseNext] = request.maxByteGapMs;
        requestDurationsMs[requestPauseNext] = durationMs;
        requestPauseNext = (requestPauseNext + 1) % PAUSE_REQUESTS;
        requestPauseCount = Math.min(requestPauseCount + 1, PAUSE_REQUESTS);
        if (requestPauseCount >= MIN_PAUSE_REQUESTS) {
            pauseLimitMs = Math.max(ABNORMAL_BYTE_GAP_MS, Math.round(PAUSE_LIMIT_FACTOR * median(requestPausesMs, requestPauseCount)));
        }
        typicalDurationMs = median(requestDurationsMs, requestPauseCount);
    }

    private static long median(long[] values, int count) {
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[count / 2];
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
            evaluateRate(request, slot * SLOT_MS, request.bytes - count);
        }
        request.slotBytes[(int) Math.floorMod(slot, SLOTS)] += count;
        if (request.pendingFromMs >= 0 && hasCaughtUp(request, now)) confirmSlow(request, now);
    }

    private void evaluateRate(Request request, long windowEndMs, long bytesBeforeWindowEnd) {
        double usualBytesPerMs = usualBytesPerMs();
        if (usualBytesPerMs <= 0 || activeMediaRequests() != 1) {
            endLowRate(request, windowEndMs);
            return;
        }
        long longMs = windowMs(LONG_WINDOW_MS);
        long shortMs = windowMs(SHORT_WINDOW_MS);
        long windowMs = isBelow(request, windowEndMs, longMs, LONG_WINDOW_MIN_RATE, usualBytesPerMs) ? longMs
            : isBelow(request, windowEndMs, shortMs, SHORT_WINDOW_MIN_RATE, usualBytesPerMs) ? shortMs : -1;
        if (windowMs < 0) {
            endLowRate(request, windowEndMs);
            return;
        }
        if (request.lowRateFromMs < 0) request.lowRateFromMs = windowEndMs - windowMs;
        if (request.pendingFromMs < 0) {
            request.pendingFromMs = windowEndMs - windowMs;
            request.pendingBaseBytes = bytesBeforeWindowEnd - windowBytes(request, windowEndMs, windowMs);
        }
    }

    private boolean hasCaughtUp(Request request, long now) {
        double usualBytesPerMs = usualBytesPerMs();
        return usualBytesPerMs > 0 &&
            request.bytes - request.pendingBaseBytes >= CATCH_UP_RATE * usualBytesPerMs * (now - request.pendingFromMs);
    }

    private static long windowBytes(Request request, long windowEndMs, long windowMs) {
        long bytes = 0;
        long lastSlot = windowEndMs / SLOT_MS - 1;
        for (long i = 0; i < windowMs / SLOT_MS; i++) bytes += request.slotBytes[(int) Math.floorMod(lastSlot - i, SLOTS)];
        return bytes;
    }

    private static boolean isBelow(Request request, long windowEndMs, long windowMs, double minRate, double usualBytesPerMs) {
        if (windowEndMs - windowMs < request.startMs) return false;
        return windowBytes(request, windowEndMs, windowMs) < minRate * usualBytesPerMs * windowMs;
    }

    private void endLowRate(Request request, long endMs) {
        if (request.lowRateFromMs < 0) return;
        request.lowRateMs += endMs - request.lowRateFromMs;
        request.lowRateFromMs = -1;
    }

    private void confirmSlow(Request request, long endMs) {
        recordAbnormal(request.pendingFromMs, endMs);
        request.slowMs += endMs - request.pendingFromMs;
        request.pendingFromMs = -1;
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
        long bytes = 0, mediaNoByteMs = -1, readWaitMs = -1, openWaitMs = -1, lowRateMs = -1;
        for (Request request : active) {
            if (!request.media) continue;
            count++;
            bytes += request.bytes;
            mediaNoByteMs = Math.max(mediaNoByteMs, noByteMs(request, now));
            if (request.readStartMs >= 0) readWaitMs = Math.max(readWaitMs, now - request.readStartMs);
            if (request.openMs < 0) openWaitMs = Math.max(openWaitMs, now - request.startMs);
            if (request.lowRateFromMs >= 0) lowRateMs = Math.max(lowRateMs, now - request.lowRateFromMs);
        }
        return " deliveryId=" + sourceId + " mediaRequests=" + count + " mediaBytes=" + bytes +
            " mediaNoByteMs=" + mediaNoByteMs + " mediaReadWaitMs=" + readWaitMs + " mediaOpenWaitMs=" + openWaitMs +
            " mediaLowRateMs=" + lowRateMs + " pauseLimitMs=" + pauseLimitMs;
    }

    private static long noByteMs(Request request, long now) {
        return now - (request.lastByteMs < 0 ? request.startMs : request.lastByteMs);
    }

    static final class Request {
        final long id, startMs;
        final boolean media;
        final long[] slotBytes = new long[SLOTS];
        long openMs = -1, firstByteMs = -1, lastByteMs = -1, readStartMs = -1, slot = -1;
        long lowRateFromMs = -1, pendingFromMs = -1, pendingBaseBytes;
        long bytes, maxByteGapMs, maxReadWaitMs, lowRateMs, slowMs;
        Request(long id, long startMs, boolean media) { this.id = id; this.startMs = startMs; this.media = media; }
    }
}
