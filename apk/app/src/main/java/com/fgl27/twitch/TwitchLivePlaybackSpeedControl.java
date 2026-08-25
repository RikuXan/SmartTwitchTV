/*
 * Copyright (c) 2026 SmartTwitchTV low latency fork
 *
 * SmartTwitchTV is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.fgl27.twitch;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem.LiveConfiguration;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.LivePlaybackSpeedControl;

//Steers playback speed to hold the windowed minimum of the buffered duration at the configured
//cushion. The buffer running dry is what actually stalls playback, a live offset target cannot see
//delivery side shortfalls and would need per stream tuning; the windowed minimum sizes the average
//buffer from the stream's own delivery jitter before the first stall ever happens.
public final class TwitchLivePlaybackSpeedControl implements LivePlaybackSpeedControl {

    private static final int WINDOW_BUCKETS = 10;
    private static final long BUCKET_MS = 1000;
    private static final long DEADBAND_US = 150_000;
    private static final float PROPORTIONAL_FACTOR = 0.1f / C.MICROS_PER_SECOND;
    private static final long STALL_BUMP_US = 250_000;
    private static final long STALL_EXTRA_MAX_US = 1_500_000;
    private static final long STALL_DECAY_HOLD_MS = 120_000;
    private static final long STALL_DECAY_US_PER_SECOND = 25_000;
    private static final long MIN_UPDATE_INTERVAL_MS = 500;

    private final long[] bucketMinUs = new long[WINDOW_BUCKETS];
    private long lastBucket = Long.MIN_VALUE;

    private long cushionUs = C.TIME_UNSET;
    private float minPlaybackSpeed = 1f;
    private float maxPlaybackSpeed = 1f;
    private long stallExtraUs = 0;
    private long lastStallMs = C.TIME_UNSET;
    private long lastDecayTickMs = C.TIME_UNSET;
    private long lastUpdateMs = C.TIME_UNSET;
    private float adjustedSpeed = 1f;

    @Override
    public void setLiveConfiguration(LiveConfiguration liveConfiguration) {
        cushionUs = liveConfiguration.targetOffsetMs != C.TIME_UNSET ? Util.msToUs(liveConfiguration.targetOffsetMs) : C.TIME_UNSET;
        minPlaybackSpeed = liveConfiguration.minPlaybackSpeed != C.RATE_UNSET ? liveConfiguration.minPlaybackSpeed : 1f;
        maxPlaybackSpeed = liveConfiguration.maxPlaybackSpeed != C.RATE_UNSET ? liveConfiguration.maxPlaybackSpeed : 1f;
    }

    @Override
    public void setTargetLiveOffsetOverrideUs(long liveOffsetUs) {
        //Seek based overrides don't apply, the control target is the buffer cushion
    }

    @Override
    public void notifyRebuffer() {
        stallExtraUs = Math.min(stallExtraUs + STALL_BUMP_US, STALL_EXTRA_MAX_US);
        lastStallMs = SystemClock.elapsedRealtime();
        lastUpdateMs = C.TIME_UNSET;
    }

    @Override
    public float getAdjustedPlaybackSpeed(long liveOffsetUs, long bufferedDurationUs) {
        if (cushionUs == C.TIME_UNSET || minPlaybackSpeed == maxPlaybackSpeed) return 1f;

        long nowMs = SystemClock.elapsedRealtime();
        recordBuffer(nowMs, bufferedDurationUs);
        decayStallExtra(nowMs);

        if (lastUpdateMs != C.TIME_UNSET && nowMs - lastUpdateMs < MIN_UPDATE_INTERVAL_MS) {
            return adjustedSpeed;
        }
        lastUpdateMs = nowMs;

        long errorUs = windowedMinUs() - (cushionUs + stallExtraUs);
        adjustedSpeed = Math.abs(errorUs) <= DEADBAND_US
            ? 1f
            : Util.constrainValue(1f + PROPORTIONAL_FACTOR * errorUs, minPlaybackSpeed, maxPlaybackSpeed);

        return adjustedSpeed;
    }

    @Override
    public long getTargetLiveOffsetUs() {
        return C.TIME_UNSET;
    }

    public long getWindowedMinMs() {
        return Util.usToMs(windowedMinUs());
    }

    public long getStallExtraMs() {
        return Util.usToMs(stallExtraUs);
    }

    public float getAdjustedSpeed() {
        return adjustedSpeed;
    }

    private void recordBuffer(long nowMs, long bufferedDurationUs) {
        long bucket = nowMs / BUCKET_MS;

        if (bucket != lastBucket) {
            long clear = lastBucket == Long.MIN_VALUE ? WINDOW_BUCKETS : Math.min(bucket - lastBucket, (long) WINDOW_BUCKETS);
            for (long b = 0; b < clear; b++) {
                bucketMinUs[(int) Math.floorMod(bucket - b, WINDOW_BUCKETS)] = Long.MAX_VALUE;
            }
            lastBucket = bucket;
        }

        int index = (int) Math.floorMod(bucket, WINDOW_BUCKETS);
        bucketMinUs[index] = Math.min(bucketMinUs[index], bufferedDurationUs);
    }

    private long windowedMinUs() {
        long min = Long.MAX_VALUE;
        for (long bucketValue : bucketMinUs) {
            min = Math.min(min, bucketValue);
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    private void decayStallExtra(long nowMs) {
        if (stallExtraUs > 0 && lastStallMs != C.TIME_UNSET && nowMs - lastStallMs > STALL_DECAY_HOLD_MS) {
            if (lastDecayTickMs != C.TIME_UNSET) {
                stallExtraUs = Math.max(0, stallExtraUs - (nowMs - lastDecayTickMs) * STALL_DECAY_US_PER_SECOND / 1000);
            }
            lastDecayTickMs = nowMs;
        } else {
            lastDecayTickMs = C.TIME_UNSET;
        }
    }
}
