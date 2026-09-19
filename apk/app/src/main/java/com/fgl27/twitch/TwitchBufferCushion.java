package com.fgl27.twitch;

import androidx.media3.common.C;
import androidx.media3.datasource.HttpDataSource;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

final class TwitchBufferCushion {
    private static final long BUMP_US = 250000;
    private static final long MAX_EXTRA_US = 1500000;
    private static final long FAILURE_MEMORY_MS = 1800000;
    private static final long RECOVERY_HOLD_MS = 120000;
    private static final long PROBE_INTERVAL_MS = 300000;
    private static final long MAX_PROBE_INTERVAL_MS = 1800000;
    private static final long PROBE_STEP_US = 50000;
    private static final long ERROR_WINDOW_MS = 5000;

    private long learnedUs;
    private long recoveryUs;
    private long recoveryBeforeStallUs;
    private long raisedExtraUs;
    private long lastFailureMs = C.TIME_UNSET;
    private long lastErrorMs = C.TIME_UNSET;
    private long recoveryAtMs = C.TIME_UNSET;
    private long pendingAtMs = C.TIME_UNSET;
    private long lastTickMs = C.TIME_UNSET;
    private long stableMs;
    private long probeIntervalMs = PROBE_INTERVAL_MS;
    private long probeSafeUs;
    private boolean originSeeded;
    private boolean confirmedByStalls;
    private boolean probeSafeConfirmed;
    private boolean probing;
    private boolean excluded;

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
        learnedUs = recoveryUs = stableMs = probeSafeUs = 0;
        lastFailureMs = lastErrorMs = recoveryAtMs = pendingAtMs = lastTickMs = C.TIME_UNSET;
        probeIntervalMs = PROBE_INTERVAL_MS;
        originSeeded = confirmedByStalls = probeSafeConfirmed = probing = excluded = false;
    }

    synchronized void seedOriginMs(long originMs) {
        if (originSeeded || originMs < 0) return;
        originSeeded = true;
        learnedUs = Math.max(learnedUs, Math.min(MAX_EXTRA_US, originMs * 1000));
        stableMs = 0;
    }

    synchronized void rebuffer(long nowMs) {
        recoveryBeforeStallUs = recoveryUs;
        raisedExtraUs = Math.min(MAX_EXTRA_US, getExtraUs() + BUMP_US);
        recoveryUs = raisedExtraUs;
        pendingAtMs = nowMs;
        excluded = lastErrorMs != C.TIME_UNSET && nowMs - lastErrorMs <= ERROR_WINDOW_MS;
        stableMs = 0;
    }

    synchronized void deliveryError(long nowMs) {
        lastErrorMs = nowMs;
        stableMs = 0;
        if (pendingAtMs != C.TIME_UNSET) excluded = true;
    }

    synchronized void recovered(long nowMs) {
        if (pendingAtMs == C.TIME_UNSET || nowMs < pendingAtMs) return;
        if (excluded) {
            recoveryUs = recoveryBeforeStallUs;
        } else {
            if (probing) {
                learnedUs = Math.max(learnedUs, probeSafeUs);
                confirmedByStalls = probeSafeConfirmed;
                probeIntervalMs = Math.min(MAX_PROBE_INTERVAL_MS, probeIntervalMs * 2);
                probing = false;
            } else if (confirmedByStalls || (lastFailureMs != C.TIME_UNSET && nowMs - lastFailureMs <= FAILURE_MEMORY_MS)) {
                learnedUs = Math.max(learnedUs, raisedExtraUs);
                confirmedByStalls = true;
            }
            lastFailureMs = nowMs;
            recoveryAtMs = nowMs;
        }
        pendingAtMs = C.TIME_UNSET;
        stableMs = 0;
        lastTickMs = nowMs;
    }

    synchronized void tick(long nowMs, boolean settled) {
        long elapsedMs = lastTickMs == C.TIME_UNSET ? 0 : nowMs - lastTickMs;
        lastTickMs = nowMs;
        if (elapsedMs < 0 || elapsedMs > 1000) {
            stableMs = 0;
            return;
        }
        if (pendingAtMs != C.TIME_UNSET) return;
        if (recoveryAtMs != C.TIME_UNSET && nowMs > recoveryAtMs + RECOVERY_HOLD_MS) {
            long decayMs = Math.min(elapsedMs, nowMs - recoveryAtMs - RECOVERY_HOLD_MS);
            recoveryUs = Math.max(0, recoveryUs - decayMs * 25);
        }
        if (!settled || recoveryUs > learnedUs || (lastErrorMs != C.TIME_UNSET && nowMs - lastErrorMs <= ERROR_WINDOW_MS)) return;
        stableMs += elapsedMs;
        if (stableMs < probeIntervalMs) return;
        stableMs = 0;
        if (learnedUs == 0) {
            probing = false;
            return;
        }
        probeSafeUs = learnedUs;
        probeSafeConfirmed = confirmedByStalls;
        learnedUs = Math.max(0, learnedUs - PROBE_STEP_US);
        if (learnedUs == 0) confirmedByStalls = false;
        recoveryUs = Math.min(recoveryUs, learnedUs);
        probing = true;
    }

    synchronized boolean isConfirmedByStalls() { return confirmedByStalls; }
    synchronized boolean wasExcluded() { return excluded; }
    synchronized long getExtraUs() { return Math.max(learnedUs, recoveryUs); }
    synchronized long getLearnedMs() { return learnedUs / 1000; }
    synchronized long getProbeIntervalMs() { return probeIntervalMs; }
    synchronized boolean isProbing() { return probing; }
}
