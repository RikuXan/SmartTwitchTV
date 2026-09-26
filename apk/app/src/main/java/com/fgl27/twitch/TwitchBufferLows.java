package com.fgl27.twitch;

import androidx.media3.common.C;
import java.util.Arrays;

final class TwitchBufferLows {
    static final long MARGIN_US = 150000;
    private static final long BUCKET_MS = 10000;
    private static final int BUCKETS = 120;
    private static final int MIN_RAISE_BUCKETS = 3;
    private static final int MIN_TRIM_BUCKETS = 18;
    private static final long MIN_COVERED_MS = BUCKET_MS / 2;
    private static final long MAX_SAMPLE_GAP_MS = 1000;
    private static final long COMMIT_DELAY_MS = TwitchDeliveryProgress.ABNORMAL_BYTE_GAP_MS + 100;
    private static final long EXCLUSION_AFTER_MS = 15000;
    private static final int PENDING = 128;

    private final long[] lowsUs = new long[BUCKETS];
    private final long[] coveredMs = new long[BUCKETS];
    private final long[] pendingAtMs = new long[PENDING];
    private final long[] pendingUs = new long[PENDING];
    private int pendingHead;
    private int pendingCount;
    private long lastBucket;
    private long lastCommittedMs;
    private long excludeFromMs;
    private long excludeUntilMs;

    TwitchBufferLows() {
        interrupt();
    }

    synchronized void interrupt() {
        clearBuckets();
        pendingHead = pendingCount = 0;
        excludeFromMs = excludeUntilMs = -1;
    }

    synchronized void exclude(long fromMs, long toMs) {
        if (fromMs < 0) return;
        if (excludeFromMs == -1 || fromMs > excludeUntilMs) excludeFromMs = fromMs;
        excludeUntilMs = Math.max(excludeUntilMs, toMs + EXCLUSION_AFTER_MS);
    }

    synchronized void sample(long nowMs, long bufferUs, long advanceUs) {
        if (pendingCount == PENDING) commitOldest();
        int tail = (pendingHead + pendingCount) % PENDING;
        pendingAtMs[tail] = nowMs;
        pendingUs[tail] = bufferUs + advanceUs;
        pendingCount++;
        while (pendingCount > 0 && nowMs - pendingAtMs[pendingHead] >= COMMIT_DELAY_MS) commitOldest();
    }

    synchronized long recommendedTargetUs() {
        long[] typical = coveredLows();
        if (typical.length < MIN_RAISE_BUCKETS) return C.TIME_UNSET;
        return MARGIN_US + Math.max(0, typical[typical.length / 2] - worstUs());
    }

    synchronized boolean isTrimReady() {
        return coveredLows().length >= MIN_TRIM_BUCKETS;
    }

    synchronized String snapshot(long advanceUs) {
        long[] typical = coveredLows();
        long worstUs = worstUs();
        long recommendedUs = recommendedTargetUs();
        return " lowsBuckets=" + typical.length +
            " lowsTypicalMs=" + (typical.length > 0 ? (typical[typical.length / 2] - advanceUs) / 1000 : -1) +
            " lowsWorstMs=" + (worstUs != Long.MAX_VALUE ? (worstUs - advanceUs) / 1000 : -1) +
            " lowsTargetMs=" + (recommendedUs != C.TIME_UNSET ? recommendedUs / 1000 : -1);
    }

    private void commitOldest() {
        long atMs = pendingAtMs[pendingHead];
        long valueUs = pendingUs[pendingHead];
        pendingHead = (pendingHead + 1) % PENDING;
        pendingCount--;
        if (excludeFromMs != -1 && atMs >= excludeFromMs && atMs <= excludeUntilMs) {
            lastCommittedMs = -1;
            return;
        }
        long bucket = atMs / BUCKET_MS;
        if (lastBucket != -1 && (bucket < lastBucket || bucket - lastBucket >= BUCKETS)) clearBuckets();
        long clear = lastBucket == -1 ? BUCKETS : bucket - lastBucket;
        for (long i = 0; i < clear; i++) {
            int index = (int) Math.floorMod(bucket - i, BUCKETS);
            lowsUs[index] = Long.MAX_VALUE;
            coveredMs[index] = 0;
        }
        lastBucket = bucket;
        int index = (int) Math.floorMod(bucket, BUCKETS);
        lowsUs[index] = Math.min(lowsUs[index], valueUs);
        if (lastCommittedMs != -1 && atMs - lastCommittedMs <= MAX_SAMPLE_GAP_MS) coveredMs[index] += atMs - lastCommittedMs;
        lastCommittedMs = atMs;
    }

    private void clearBuckets() {
        Arrays.fill(lowsUs, Long.MAX_VALUE);
        Arrays.fill(coveredMs, 0);
        lastBucket = lastCommittedMs = -1;
    }

    private long[] coveredLows() {
        long[] covered = new long[BUCKETS];
        int count = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (coveredMs[i] >= MIN_COVERED_MS) covered[count++] = lowsUs[i];
        }
        long[] sorted = Arrays.copyOf(covered, count);
        Arrays.sort(sorted);
        return sorted;
    }

    private long worstUs() {
        long worst = Long.MAX_VALUE;
        for (long low : lowsUs) worst = Math.min(worst, low);
        return worst;
    }
}
