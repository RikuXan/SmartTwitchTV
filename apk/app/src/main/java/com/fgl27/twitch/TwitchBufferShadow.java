package com.fgl27.twitch;

import java.util.Arrays;

final class TwitchBufferShadow {
    private static final int BUCKETS = 120;
    private static final long MARGIN_US = 150000;
    private final long[] minima = new long[BUCKETS];
    private long lastBucket = -1;
    private long startedMs = -1;
    private long blockedUntilMs;
    private long observedMs;
    private long headroomUs;
    private long targetUs;
    private boolean ready;
    private int ordinaryStalls;
    private int excludedStalls;

    synchronized void reset() {
        ordinaryStalls = excludedStalls = 0;
        blockedUntilMs = 0;
        interrupt();
    }

    synchronized void interrupt() {
        Arrays.fill(minima, Long.MAX_VALUE);
        lastBucket = startedMs = -1;
        observedMs = 0;
        ready = false;
    }

    synchronized void deliveryError(long nowMs) {
        interrupt();
        blockedUntilMs = nowMs + 5000;
    }

    synchronized void recovered(boolean excluded) {
        if (excluded) excludedStalls++;
        else ordinaryStalls++;
        interrupt();
    }

    synchronized void sample(long nowMs, long bufferUs, long advanceUs, long floorUs, long effectiveTargetUs) {
        if (nowMs < blockedUntilMs) return;
        long bucket = nowMs / 1000;
        if (lastBucket != -1 && (bucket < lastBucket || bucket - lastBucket >= BUCKETS)) interrupt();
        if (startedMs == -1) startedMs = nowMs;
        long clear = lastBucket == -1 ? BUCKETS : bucket - lastBucket;
        for (long i = 0; i < clear; i++) minima[(int) Math.floorMod(bucket - i, BUCKETS)] = Long.MAX_VALUE;
        lastBucket = bucket;
        int index = (int) Math.floorMod(bucket, BUCKETS);
        minima[index] = Math.min(minima[index], bufferUs + advanceUs);
        long minimum = Long.MAX_VALUE;
        for (long value : minima) minimum = Math.min(minimum, value);
        headroomUs = minimum - advanceUs - MARGIN_US;
        targetUs = Math.max(floorUs, effectiveTargetUs - headroomUs);
        observedMs = Math.min(119000, nowMs - startedMs);
        ready = observedMs >= 60000;
    }

    synchronized String snapshot() {
        return " shadowReady=" + ready + " shadowObservedMs=" + observedMs +
            " shadowHeadroomMs=" + (ready ? headroomUs / 1000 : -1) +
            " shadowTargetMs=" + (ready ? targetUs / 1000 : -1) +
            " shadowMarginMs=150 shadowOrdinaryStalls=" + ordinaryStalls +
            " shadowExcludedStalls=" + excludedStalls;
    }
}
