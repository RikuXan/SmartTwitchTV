package com.fgl27.twitch;

final class TwitchRecoveryPolicy {
    private long lastStallMs = -1;
    private long fallbackUntilMs;

    synchronized void reset() { lastStallMs = -1; fallbackUntilMs = 0; }

    synchronized void stall(long nowMs) {
        if (lastStallMs >= 0 && nowMs - lastStallMs <= 30000) deliveryError(nowMs);
        lastStallMs = nowMs;
    }

    synchronized void deliveryError(long nowMs) { fallbackUntilMs = nowMs + 60000; }

    synchronized long thresholdMs(long nowMs, long cushionMs, long legacyMs) {
        if (cushionMs < 0 || nowMs < fallbackUntilMs) return legacyMs;
        return Math.min(legacyMs, Math.max(500, cushionMs + 150));
    }
}
