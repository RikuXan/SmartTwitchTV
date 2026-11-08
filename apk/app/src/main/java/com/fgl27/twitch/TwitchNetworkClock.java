package com.fgl27.twitch;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.util.SntpClient;
import java.io.IOException;

final class TwitchNetworkClock {

    private static final long REFRESH_INTERVAL_MS = 30000;
    static final long MAX_OFFSET_AGE_MS = 2 * REFRESH_INTERVAL_MS;
    private static long offsetMs = C.TIME_UNSET;
    private static long refreshedAtMs = C.TIME_UNSET;
    private static long lastAttemptMs = C.TIME_UNSET;
    private static Loader loader;

    static synchronized long getOffsetMs() {
        return currentOffsetMs(offsetMs, refreshedAtMs, SystemClock.elapsedRealtime());
    }

    //An old offset must read as unset: a source anchored on it would show the drift since then as latency.
    static long currentOffsetMs(long offsetMs, long refreshedAtMs, long nowMs) {
        return refreshedAtMs != C.TIME_UNSET && nowMs - refreshedAtMs <= MAX_OFFSET_AGE_MS ? offsetMs : C.TIME_UNSET;
    }

    private static synchronized void update(long refreshedOffsetMs) {
        long changeMs = offsetMs == C.TIME_UNSET ? 0 : refreshedOffsetMs - offsetMs;
        offsetMs = refreshedOffsetMs;
        refreshedAtMs = SystemClock.elapsedRealtime();
        TwitchDiagnosticLog.i("LTB-CLOCK-REFRESH offsetMs=" + offsetMs + " changeMs=" + changeMs);
    }

    static void refreshIfDue() {
        long nowMs = SystemClock.elapsedRealtime();
        if (lastAttemptMs != C.TIME_UNSET && nowMs - lastAttemptMs < REFRESH_INTERVAL_MS) return;
        if (loader == null) {
            loader = new Loader("TwitchClock");
            SntpClient.setMaxElapsedTimeUntilUpdateMs(REFRESH_INTERVAL_MS);
        }
        if (loader.isLoading()) return;
        lastAttemptMs = nowMs;
        loader.clearFatalError();
        SntpClient.initialize(loader, new SntpClient.InitializationCallback() {
            @Override
            public void onInitialized() {
                long refreshedOffsetMs = SntpClient.getElapsedRealtimeOffsetMs();
                if (refreshedOffsetMs != C.TIME_UNSET) update(refreshedOffsetMs);
            }

            @Override
            public void onInitializationFailed(IOException error) {
                TwitchDiagnosticLog.i("LTB-CLOCK-REFRESH failed=" + error.getClass().getSimpleName());
            }
        });
    }
}
