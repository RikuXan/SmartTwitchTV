package com.fgl27.twitch;

import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.HttpDataSource;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class TwitchBufferCushion {
    static final long START_RESERVE_US = 200000;
    private static final long BUMP_US = 250000;
    private static final long MAX_EXTRA_US = 1500000;
    private static final long ERROR_WINDOW_MS = 15000;
    private static final long TRIM_INTERVAL_MS = 180000;
    private static final long TRIM_HOLD_AFTER_STALL_MS = 1200000;
    private static final long HYSTERESIS_US = 25000;

    private final long startReserveUs;
    private long learnedUs;
    private long raisedExtraUs;
    private long lastErrorMs;
    private long pendingAtMs;
    private long lastChangeMs;
    private long trimHoldUntilMs;
    private boolean originSeeded;
    private boolean excluded;

    TwitchBufferCushion() {
        this(START_RESERVE_US);
    }

    TwitchBufferCushion(long startReserveUs) {
        this.startReserveUs = startReserveUs;
        reset();
    }

    static boolean isUnusualDeliveryError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof UnknownHostException ||
                cause instanceof ConnectException || cause instanceof NoRouteToHostException) return true;
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return isUnusualHttpStatus(status);
            }
        }
        return false;
    }

    static boolean isUnusualHttpStatus(int status) {
        return status == 401 || status == 403 || status == 429 || status >= 500;
    }

    synchronized void reset() {
        learnedUs = startReserveUs;
        raisedExtraUs = 0;
        lastErrorMs = pendingAtMs = lastChangeMs = trimHoldUntilMs = C.TIME_UNSET;
        originSeeded = excluded = false;
    }

    synchronized void seedOriginMs(long originMs) {
        if (originSeeded || originMs < 0) return;
        originSeeded = true;
        learnedUs = Math.max(learnedUs, Math.min(MAX_EXTRA_US, originMs * 1000));
    }

    synchronized void rebuffer(long nowMs) {
        raisedExtraUs = Math.min(MAX_EXTRA_US, getExtraUs() + BUMP_US);
        pendingAtMs = nowMs;
        excluded = lastErrorMs != C.TIME_UNSET && nowMs - lastErrorMs <= ERROR_WINDOW_MS;
    }

    synchronized void deliveryError(long atMs) {
        if (lastErrorMs == C.TIME_UNSET || atMs > lastErrorMs) lastErrorMs = atMs;
        if (pendingAtMs != C.TIME_UNSET && atMs >= pendingAtMs - ERROR_WINDOW_MS) excluded = true;
    }

    synchronized void recovered(long nowMs) {
        if (pendingAtMs == C.TIME_UNSET || nowMs < pendingAtMs) return;
        if (!excluded) {
            learnedUs = raisedExtraUs;
            lastChangeMs = nowMs;
            trimHoldUntilMs = nowMs + TRIM_HOLD_AFTER_STALL_MS;
        }
        pendingAtMs = C.TIME_UNSET;
    }

    synchronized void resize(long nowMs, long recommendedExtraUs, boolean trimReady) {
        if (pendingAtMs != C.TIME_UNSET || recommendedExtraUs == C.TIME_UNSET) return;
        long targetUs = Util.constrainValue(recommendedExtraUs, 0, MAX_EXTRA_US);
        if (targetUs > learnedUs + HYSTERESIS_US) {
            learnedUs = targetUs;
            lastChangeMs = nowMs;
        } else if (targetUs < learnedUs - HYSTERESIS_US && trimReady &&
            (lastChangeMs == C.TIME_UNSET || nowMs - lastChangeMs >= TRIM_INTERVAL_MS) &&
            (trimHoldUntilMs == C.TIME_UNSET || nowMs >= trimHoldUntilMs)) {
            learnedUs = targetUs;
            lastChangeMs = nowMs;
        }
    }

    synchronized boolean wasExcluded() { return excluded; }
    synchronized long getExtraUs() { return pendingAtMs != C.TIME_UNSET ? Math.max(learnedUs, raisedExtraUs) : learnedUs; }
    synchronized long getLearnedMs() { return learnedUs / 1000; }
    synchronized long getTrimHoldMs(long nowMs) {
        return trimHoldUntilMs == C.TIME_UNSET || nowMs >= trimHoldUntilMs ? 0 : trimHoldUntilMs - nowMs;
    }
}
