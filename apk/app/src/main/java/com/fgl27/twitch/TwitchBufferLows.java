package com.fgl27.twitch;

import androidx.media3.common.C;
import java.util.Arrays;

final class TwitchBufferLows {
    static final long MARGIN_US = 150000;
    private static final long BUCKET_MS = 10000;
    private static final int BUCKETS = 120;
    private static final int LEVEL_BUCKETS = 6;
    private static final int MIN_LEVEL_BUCKETS = 3;
    private static final int MIN_TRIM_BUCKETS = 18;
    private static final long MIN_COVERED_MS = BUCKET_MS / 2;
    private static final long MAX_SAMPLE_GAP_MS = 1000;
    private static final long COMMIT_DELAY_MS = TwitchDeliveryProgress.DETECTION_DELAY_MS + 100;
    private static final long EXCLUSION_AFTER_MS = 15000;
    private static final int PENDING = 512;

    private final long[] depthUs = new long[BUCKETS];
    private final long[] depthBucket = new long[BUCKETS];
    private final boolean[] depthCovered = new boolean[BUCKETS];
    private final long[] levelLowsUs = new long[LEVEL_BUCKETS];
    private int levelCount;
    private int levelNext;
    private final long[] pendingAtMs = new long[PENDING];
    private final long[] pendingUs = new long[PENDING];
    private int pendingHead;
    private int pendingCount;
    private long bucket;
    private long bucketLowUs;
    private long bucketCoveredMs;
    private long lastCommittedMs;
    private long latestBucket;
    private long excludeFromMs;
    private long excludeUntilMs;

    TwitchBufferLows() {
        clear();
    }

    synchronized void clear() {
        Arrays.fill(depthBucket, -1);
        latestBucket = -1;
        excludeFromMs = excludeUntilMs = -1;
        restartLevel();
    }

    synchronized void interrupt() {
        finishBucket();
        restartLevel();
    }

    synchronized void exclude(long fromMs, long toMs) {
        if (fromMs < 0) return;
        if (excludeFromMs == -1 || fromMs > excludeUntilMs) excludeFromMs = fromMs;
        excludeUntilMs = Math.max(excludeUntilMs, toMs + EXCLUSION_AFTER_MS);
    }

    synchronized void sample(long nowMs, long bufferUs, long advanceUs) {
        latestBucket = Math.max(latestBucket, nowMs / BUCKET_MS);
        if (pendingCount == PENDING) commitOldest();
        int tail = (pendingHead + pendingCount) % PENDING;
        pendingAtMs[tail] = nowMs;
        pendingUs[tail] = bufferUs + advanceUs;
        pendingCount++;
        while (pendingCount > 0 && nowMs - pendingAtMs[pendingHead] >= COMMIT_DELAY_MS) commitOldest();
    }

    synchronized long recommendedTargetUs() {
        long deepestUs = deepestUs();
        return deepestUs == Long.MIN_VALUE ? C.TIME_UNSET : MARGIN_US + Math.max(0, deepestUs);
    }

    synchronized boolean isTrimReady() {
        int covered = bucketCoveredMs >= MIN_COVERED_MS && currentDepthUs() != Long.MIN_VALUE ? 1 : 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (isLive(i) && depthCovered[i]) covered++;
        }
        return covered >= MIN_TRIM_BUCKETS;
    }

    synchronized String snapshot(long advanceUs) {
        int measured = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (isLive(i) && depthCovered[i]) measured++;
        }
        long levelUs = levelUs();
        long deepestUs = deepestUs();
        return " lowsBuckets=" + measured +
            " lowsLevelMs=" + (levelUs != Long.MIN_VALUE ? (levelUs - advanceUs) / 1000 : -1) +
            " lowsDepthMs=" + (deepestUs != Long.MIN_VALUE ? deepestUs / 1000 : -1) +
            " lowsTargetMs=" + (deepestUs != Long.MIN_VALUE ? recommendedTargetUs() / 1000 : -1);
    }

    private void restartLevel() {
        levelCount = levelNext = 0;
        bucket = lastCommittedMs = -1;
        bucketLowUs = Long.MAX_VALUE;
        bucketCoveredMs = 0;
        pendingHead = pendingCount = 0;
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
        long atBucket = atMs / BUCKET_MS;
        if (atBucket != bucket) {
            finishBucket();
            bucket = atBucket;
        }
        bucketLowUs = Math.min(bucketLowUs, valueUs);
        if (lastCommittedMs != -1 && atMs - lastCommittedMs <= MAX_SAMPLE_GAP_MS) bucketCoveredMs += atMs - lastCommittedMs;
        lastCommittedMs = atMs;
    }

    private void finishBucket() {
        if (bucket == -1 || bucketLowUs == Long.MAX_VALUE) return;
        long depth = currentDepthUs();
        if (depth != Long.MIN_VALUE) {
            int index = (int) Math.floorMod(bucket, BUCKETS);
            depthUs[index] = depth;
            depthBucket[index] = bucket;
            depthCovered[index] = bucketCoveredMs >= MIN_COVERED_MS;
        }
        if (bucketCoveredMs >= MIN_COVERED_MS) {
            levelLowsUs[levelNext] = bucketLowUs;
            levelNext = (levelNext + 1) % LEVEL_BUCKETS;
            levelCount = Math.min(levelCount + 1, LEVEL_BUCKETS);
        }
        bucket = -1;
        bucketLowUs = Long.MAX_VALUE;
        bucketCoveredMs = 0;
    }

    private long currentDepthUs() {
        long levelUs = levelUs();
        return levelUs == Long.MIN_VALUE || bucketLowUs == Long.MAX_VALUE ? Long.MIN_VALUE : levelUs - bucketLowUs;
    }

    private long levelUs() {
        if (levelCount < MIN_LEVEL_BUCKETS) return Long.MIN_VALUE;
        long[] sorted = Arrays.copyOf(levelLowsUs, levelCount);
        Arrays.sort(sorted);
        return sorted[levelCount / 2];
    }

    private long deepestUs() {
        long deepestUs = currentDepthUs();
        for (int i = 0; i < BUCKETS; i++) {
            if (isLive(i)) deepestUs = Math.max(deepestUs, depthUs[i]);
        }
        return deepestUs;
    }

    private boolean isLive(int index) {
        return depthBucket[index] != -1 && latestBucket - depthBucket[index] < BUCKETS;
    }
}
