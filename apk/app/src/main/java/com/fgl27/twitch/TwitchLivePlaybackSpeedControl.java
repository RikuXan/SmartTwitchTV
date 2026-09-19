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
import java.util.function.LongSupplier;

//Steers playback speed to hold the windowed minimum of the buffered duration at the configured
//cushion. The buffer running dry is what actually stalls playback, a live offset target cannot see
//delivery side shortfalls and would need per stream tuning; the windowed minimum sizes the average
//buffer from the stream's own delivery jitter before the first stall ever happens.
public final class TwitchLivePlaybackSpeedControl implements LivePlaybackSpeedControl {

    private static final int WINDOW_BUCKETS = 10;
    private static final long BUCKET_MS = 1000;
    private static final long DEADBAND_US = 50_000;
    private static final float PROPORTIONAL_FACTOR = 0.1f / C.MICROS_PER_SECOND;
    private static final long MIN_UPDATE_INTERVAL_MS = 1500;
    private static final long ARM_PLATEAU_MS = 2000;
    private static final long ARM_NEAR_PEAK_US = 200_000;
    private static final float SPEED_SLEW = 0.01f;
    private static final float SPEED_SNAP = 0.005f;
    private static final long MAX_SAMPLE_GAP_MS = BUCKET_MS;

    private final LongSupplier elapsedRealtimeMs;
    private final TwitchRecoveryPolicy recovery = new TwitchRecoveryPolicy();
    private final TwitchBufferShadow shadow = new TwitchBufferShadow();
    private boolean shadowStallPending;
    private long lastSampleMs = C.TIME_UNSET;
    private double playbackAdvanceUs;

    private final long[] bucketMinAtNormalSpeedUs = new long[WINDOW_BUCKETS];
    private long lastBucket = Long.MIN_VALUE;
    private long windowFullAtMs = C.TIME_UNSET;
    private boolean armed = false;
    private long armDeadlineMs = C.TIME_UNSET;
    private long armMaxSoFarUs = 0;
    private long armLastNewMaxMs = C.TIME_UNSET;

    private long cushionUs = C.TIME_UNSET;
    private float minPlaybackSpeed = 1f;
    private float maxPlaybackSpeed = 1f;
    private volatile TwitchBufferCushion cushion = new TwitchBufferCushion();
    private long lastUpdateMs = C.TIME_UNSET;
    private float adjustedSpeed = 1f;

    public TwitchLivePlaybackSpeedControl() {
        this(SystemClock::elapsedRealtime);
    }

    TwitchLivePlaybackSpeedControl(LongSupplier elapsedRealtimeMs) {
        this.elapsedRealtimeMs = elapsedRealtimeMs;
    }

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
        recovery.stall(elapsedRealtimeMs.getAsLong());
        shadowStallPending = true;
        cushion.rebuffer(elapsedRealtimeMs.getAsLong());
        resetWindow();
    }

    @Override
    public float getAdjustedPlaybackSpeed(long liveOffsetUs, long bufferedDurationUs) {
        if (cushionUs == C.TIME_UNSET || minPlaybackSpeed == maxPlaybackSpeed) {
            resetWindow();
            return 1f;
        }

        long nowMs = elapsedRealtimeMs.getAsLong();
        if (lastSampleMs != C.TIME_UNSET) {
            long elapsedMs = nowMs - lastSampleMs;
            if (elapsedMs < 0 || elapsedMs > MAX_SAMPLE_GAP_MS) {
                resetWindow();
            } else {
                playbackAdvanceUs += elapsedMs * 1000d * (adjustedSpeed - 1f);
            }
        }
        lastSampleMs = nowMs;

        //Samples from the startup fill ramp are not delivery jitter and must never enter the
        //window; the ramp is only over once the buffer stops setting new maxima, and arming must
        //happen near the peak so a mid-fill delivery hiccup cannot fake the plateau
        if (!armed) {
            if (armDeadlineMs == C.TIME_UNSET) armDeadlineMs = nowMs + WINDOW_BUCKETS * BUCKET_MS;
            if (bufferedDurationUs > armMaxSoFarUs) {
                armMaxSoFarUs = bufferedDurationUs;
                armLastNewMaxMs = nowMs;
            }
            if (
                (armLastNewMaxMs != C.TIME_UNSET &&
                    nowMs - armLastNewMaxMs >= ARM_PLATEAU_MS &&
                    bufferedDurationUs >= armMaxSoFarUs - ARM_NEAR_PEAK_US) ||
                nowMs >= armDeadlineMs
            ) {
                armed = true;
            } else {
                adjustedSpeed = 1f;
                return 1f;
            }
        }

        recordBuffer(nowMs, bufferedDurationUs);
        if (!shadowStallPending) {
            shadow.sample(nowMs, bufferedDurationUs, Math.round(playbackAdvanceUs), cushionUs, cushionUs + cushion.getExtraUs());
        }
        cushion.tick(nowMs, isWarm(nowMs) && Math.abs(adjustedSpeed - 1f) < 0.005f &&
            windowedMinUs() >= cushionUs + cushion.getExtraUs() - DEADBAND_US);
        long stallExtraUs = cushion.getExtraUs();

        if (adjustedSpeed > 1f && bufferedDurationUs <= cushionUs + stallExtraUs) {
            adjustedSpeed = 1f;
            lastUpdateMs = nowMs;
            return adjustedSpeed;
        }

        if (lastUpdateMs != C.TIME_UNSET && nowMs - lastUpdateMs < MIN_UPDATE_INTERVAL_MS) {
            return adjustedSpeed;
        }
        lastUpdateMs = nowMs;

        long errorUs = windowedMinUs() - (cushionUs + stallExtraUs);
        //While the window still holds the startup ramp its lows are fill artifacts, not delivery
        //jitter: shaving stays allowed, slowing down must wait for one full window of real data
        float minSpeed = isWarm(nowMs) ? minPlaybackSpeed : 1f;
        float targetSpeed = Math.abs(errorUs) <= DEADBAND_US
            ? 1f
            : Util.constrainValue(1f + PROPORTIONAL_FACTOR * errorUs, minSpeed, maxPlaybackSpeed);

        float delta = targetSpeed - adjustedSpeed;
        if (targetSpeed == 1f && Math.abs(delta) <= SPEED_SLEW + SPEED_SNAP) {
            adjustedSpeed = 1f;
        } else if (Math.abs(delta) > SPEED_SNAP) {
            adjustedSpeed += Math.copySign(Math.min(Math.abs(delta), SPEED_SLEW), delta);
        }
        adjustedSpeed = Util.constrainValue(adjustedSpeed, minSpeed, maxPlaybackSpeed);

        return adjustedSpeed;
    }

    @Override
    public long getTargetLiveOffsetUs() {
        return C.TIME_UNSET;
    }

    public void reset() {
        resetWindow();
        cushion.reset();
        shadow.reset();
        recovery.reset();
        shadowStallPending = false;
    }

    void useCushion(TwitchBufferCushion sourceCushion) {
        resetWindow();
        cushion = sourceCushion;
        shadow.reset();
        recovery.reset();
        shadowStallPending = false;
    }

    private void resetWindow() {
        shadow.interrupt();
        lastBucket = Long.MIN_VALUE;
        windowFullAtMs = C.TIME_UNSET;
        armed = false;
        armDeadlineMs = C.TIME_UNSET;
        armMaxSoFarUs = 0;
        armLastNewMaxMs = C.TIME_UNSET;
        lastSampleMs = C.TIME_UNSET;
        playbackAdvanceUs = 0;
        lastUpdateMs = C.TIME_UNSET;
        adjustedSpeed = 1f;
    }

    public long getWindowedMinMs() {
        return armed ? Util.usToMs(windowedMinUs()) : -1;
    }

    private boolean isWarm(long nowMs) {
        return windowFullAtMs != C.TIME_UNSET && nowMs >= windowFullAtMs;
    }

    public long getStallExtraMs() {
        return Util.usToMs(cushion.getExtraUs());
    }

    public float getAdjustedSpeed() {
        return adjustedSpeed;
    }

    private void recordBuffer(long nowMs, long bufferedDurationUs) {
        long bucket = nowMs / BUCKET_MS;

        if (bucket != lastBucket) {
            long clear = lastBucket == Long.MIN_VALUE ? WINDOW_BUCKETS : Math.min(bucket - lastBucket, (long) WINDOW_BUCKETS);
            for (long b = 0; b < clear; b++) {
                bucketMinAtNormalSpeedUs[(int) Math.floorMod(bucket - b, WINDOW_BUCKETS)] = Long.MAX_VALUE;
            }
            if (clear == WINDOW_BUCKETS) windowFullAtMs = nowMs + WINDOW_BUCKETS * BUCKET_MS;
            lastBucket = bucket;
        }

        int index = (int) Math.floorMod(bucket, WINDOW_BUCKETS);
        bucketMinAtNormalSpeedUs[index] = Math.min(bucketMinAtNormalSpeedUs[index], bufferedDurationUs + Math.round(playbackAdvanceUs));
    }

    private long windowedMinUs() {
        long min = Long.MAX_VALUE;
        for (long bucketValue : bucketMinAtNormalSpeedUs) {
            min = Math.min(min, bucketValue);
        }
        return min == Long.MAX_VALUE ? 0 : Math.max(0, min - Math.round(playbackAdvanceUs));
    }

    public void onDeliveryError(long realtimeMs) {
        cushion.deliveryError(realtimeMs);
        shadow.deliveryError(realtimeMs);
        recovery.deliveryError(realtimeMs);
    }

    public void onPlaybackRecovered(long realtimeMs) {
        cushion.recovered(realtimeMs);
        if (shadowStallPending) {
            shadow.recovered(cushion.wasExcluded());
            shadowStallPending = false;
        }
    }

    long getRecoveryThresholdMs(long legacyMs) {
        long targetMs = cushionUs == C.TIME_UNSET || minPlaybackSpeed >= 1f || minPlaybackSpeed == maxPlaybackSpeed
            ? -1 : Util.usToMs(cushionUs + cushion.getExtraUs());
        return recovery.thresholdMs(elapsedRealtimeMs.getAsLong(), targetMs, legacyMs);
    }

    public String getShadowSnapshot() { return shadow.snapshot(); }

    public boolean isReserveConfirmed() { return cushion.isConfirmedByStalls(); }

    public boolean wasStallExcluded() { return cushion.wasExcluded(); }
    public long getLearnedCushionMs() { return cushion.getLearnedMs(); }
    public long getProbeIntervalMs() { return cushion.getProbeIntervalMs(); }
    public boolean isProbingCushion() { return cushion.isProbing(); }
}
